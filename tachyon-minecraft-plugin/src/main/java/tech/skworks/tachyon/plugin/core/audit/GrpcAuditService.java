package tech.skworks.tachyon.plugin.core.audit;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tech.skworks.tachyon.api.services.AuditService;
import tech.skworks.tachyon.libs.io.grpc.StatusRuntimeException;
import tech.skworks.tachyon.plugin.core.metric.scraper.TachyonMetrics;
import tech.skworks.tachyon.plugin.core.grpc.AbstractGrpcService;
import tech.skworks.tachyon.plugin.spigot.config.TachyonConfig;
import tech.skworks.tachyon.service.contracts.audit.*;
import tech.skworks.tachyon.plugin.spigot.TachyonCore;
import tech.skworks.tachyon.plugin.common.util.TachyonLogger;
import tech.skworks.tachyon.plugin.core.grpc.BackendStubProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Project Tachyon
 * Class AuditManager
 *
 * @author  Jimmy (vSKAH) - 06/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class GrpcAuditService extends AbstractGrpcService implements AuditService {

    private static final TachyonLogger LOGGER = TachyonCore.getModuleLogger("AuditManager");
    private final String serverName;

    private final BlockingQueue<AuditLogEntry> buffer;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService executor;

    private final AtomicBoolean isFlushing = new AtomicBoolean(false);
    private final int drainAmountPerCycle;


    public GrpcAuditService(@Nullable TachyonMetrics tachyonMetrics, @NotNull BackendStubProvider backendStubProvider, @NotNull TachyonConfig config) {
        super(tachyonMetrics, backendStubProvider);
        final AuditConfig auditConfig = config.auditConfig();

        this.serverName = config.serverName();
        this.buffer = new LinkedBlockingQueue<>(auditConfig.bufferSize());

        this.scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("tachyon-audit-scheduler").factory());
        this.scheduler.scheduleAtFixedRate(this::triggerFlush, auditConfig.bufferFlushDelay(), auditConfig.bufferFlushDelay(), TimeUnit.SECONDS);

        this.executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("tachyon-audit-flush-vthread-", 1).factory());

        this.drainAmountPerCycle = auditConfig.bufferDrainPerCycles();
        LOGGER.info("AuditManager initialized for server '{}' — buffer capacity: {}, flush interval: {}s.", serverName, auditConfig.bufferSize(), auditConfig.bufferSize());
    }


    @Override
    public void log(@NotNull final String uuid, @NotNull final String action, @NotNull final String details) {
        final AuditLogEntry entry = AuditLogEntry.newBuilder().setUuid(uuid).setModule(serverName).setAction(action).setDetails(details).setTimestampMs(System.currentTimeMillis()).build();

        boolean accepted = buffer.offer(entry);
        if (!accepted) {
            LOGGER.error("Audit buffer full — entry dropped. Triggering emergency flush. ");
            LOGGER.error("Action: '{}' for uuid: {}", action, uuid);
            triggerFlush();
        }
    }

    private void triggerFlush() {
        if (buffer.isEmpty() || !isFlushing.compareAndSet(false, true)) {
            return;
        }

        List<AuditLogEntry> batch = new ArrayList<>();
        buffer.drainTo(batch, drainAmountPerCycle);

        if (batch.isEmpty()) {
            isFlushing.set(false);
            return;
        }

        asyncRun(executor, LOGGER, "flushAuditLogs",
                () -> backendStubProvider.getAuditStub(3).logEventBatch(LogEventBatchRequest.newBuilder().addAllEntries(batch).build()))
                .whenComplete((res, ex) -> {
            if (ex != null) {
                LOGGER.warn("gRPC call to audit service failed — requeueing {} entries.", batch.size());
                requeue(batch);
            }

            isFlushing.set(false);

            if (ex == null && !buffer.isEmpty()) {
                triggerFlush();
            }
        });
    }

    private void requeue(List<AuditLogEntry> batch) {
        int requeued = 0;
        int dropped  = 0;

        for (AuditLogEntry entry : batch) {
            if (buffer.offer(entry)) requeued++;
            else dropped++;
        }

        if (dropped > 0) LOGGER.error("{} audit entries permanently dropped (buffer full).", dropped);
        if (requeued > 0) LOGGER.warn("{} audit entries re-queued.", requeued);
    }

    @Override
    protected <T> void handleGrpcExceptions(@NotNull final String actionName, @NotNull final StatusRuntimeException ex, final CompletableFuture<T> future) {
        LOGGER.error("gRPC Status Exception during '{}': {} (Code: {})", actionName, ex.getMessage(), ex.getStatus().getCode());
        if (future != null && !future.isDone()) {
            future.completeExceptionally(ex);
        }
    }

    @Override
    public void shutdown() {
        LOGGER.info("AuditManager shutdown initiated — draining remaining buffer...");

        scheduler.shutdown();

        int remaining = buffer.size();
        if (remaining > 0) {
            LOGGER.info("Flushing {} remaining audit entries synchronously...", remaining);
            while (!buffer.isEmpty()) {
                List<AuditLogEntry> batch = new ArrayList<>();
                buffer.drainTo(batch, drainAmountPerCycle);
                if (batch.isEmpty()) break;

                try {
                    backendStubProvider.getAuditStub(3).logEventBatch(LogEventBatchRequest.newBuilder().addAllEntries(batch).build());
                } catch (Exception e) {
                    LOGGER.error(e, "Final audit flush failed. Dropping {} entries.", batch.size());
                    break;
                }
            }
        }

        executor.shutdown();

        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
            LOGGER.info("AuditManager shutdown complete. Buffer remaining: {} entries.", buffer.size());
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            LOGGER.error(e, "AuditManager shutdown interrupted — some entries may be lost.");
        }
    }
}
