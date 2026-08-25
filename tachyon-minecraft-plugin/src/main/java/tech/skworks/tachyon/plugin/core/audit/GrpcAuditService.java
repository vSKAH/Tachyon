package tech.skworks.tachyon.plugin.core.audit;

import io.grpc.CallOptions;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCalls;
import org.bson.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tech.skworks.tachyon.api.audit.AuditEntry;
import tech.skworks.tachyon.api.audit.AuditLevel;
import tech.skworks.tachyon.api.audit.AuditService;
import tech.skworks.tachyon.common.contract.AuditContract;
import tech.skworks.tachyon.common.marshaller.BsonMarshaller;
import tech.skworks.tachyon.plugin.core.metric.scraper.TachyonMetrics;
import tech.skworks.tachyon.plugin.core.grpc.AbstractGrpcService;
import tech.skworks.tachyon.plugin.spigot.config.TachyonConfig;
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
 * @version 2.0
 * @since 1.0.0-SNAPSHOT
 */
public class GrpcAuditService extends AbstractGrpcService implements AuditService {

    private static final TachyonLogger LOGGER = TachyonCore.getModuleLogger("AuditManager");

    private final String serverName;
    private final BlockingQueue<AuditEntry> buffer;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService executor;

    private final AtomicBoolean isFlushing = new AtomicBoolean(false);
    private final int drainAmountPerCycle;


    public GrpcAuditService(@Nullable TachyonMetrics tachyonMetrics, @NotNull BackendStubProvider backendStubProvider, @NotNull TachyonConfig config) {
        super(tachyonMetrics, backendStubProvider);
        final AuditConfig auditConfig = config.auditConfig();

        this.serverName = config.serverName();
        this.buffer = new LinkedBlockingQueue<>(auditConfig.bufferSize());
        this.drainAmountPerCycle = auditConfig.bufferDrainPerCycles();


        this.scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("tachyon-audit-scheduler").factory());
        this.scheduler.scheduleAtFixedRate(() -> triggerFlush(false), auditConfig.bufferFlushDelay(), auditConfig.bufferFlushDelay(), TimeUnit.SECONDS);

        this.executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("tachyon-audit-flush-vthread-", 1).factory());
        LOGGER.info("AuditManager initialized for server '{}' — buffer capacity: {}, flush interval: {}s.", serverName, auditConfig.bufferSize(), auditConfig.bufferFlushDelay());
    }

    @Override
    public void log(@NotNull AuditEntry auditEntry) {
        if (auditEntry.auditLevel() == AuditLevel.CRITICAL) {
            logDirect(auditEntry);
            return;
        }

        boolean accepted = buffer.offer(auditEntry);
        if (!accepted) {
            LOGGER.error("Audit buffer full — entry dropped. Action '{}' for uuid '{}'.", auditEntry.action(), auditEntry.playerId());
            triggerFlush(false);
        }
    }

    private RawBsonDocument toRawBson(AuditEntry auditEntry) {
        return BsonMarshaller.toRawBsonDocument(new BsonDocument()
                .append("uuid", new BsonString(auditEntry.playerId()))
                .append("module", new BsonString(auditEntry.module()))
                .append("action", new BsonString(auditEntry.action()))
                .append("description", new BsonString(auditEntry.description()))
                .append("level", new BsonString(auditEntry.auditLevel().name()))
                .append("timestamp", new BsonInt64(auditEntry.timestamp())));
    }

    private CompletableFuture<Void> logBatch(final List<RawBsonDocument> batch) {
        final var payload = BsonMarshaller.toRawBsonDocument(new BsonDocument("values", new BsonArray(batch)));

        return asyncRun(executor, LOGGER, "logBatch", () -> {
            ClientCalls.blockingUnaryCall(
                    backendStubProvider.getChannel(),
                    AuditContract.LOG_ENTRY_BATCH_METHOD,
                    CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS),
                    payload
            );
        });

    }

    private CompletableFuture<Void> logDirect(AuditEntry auditEntry) {
        final var payload = toRawBson(auditEntry);

        return asyncRun(executor, LOGGER, "logDirect", () -> {
            ClientCalls.blockingUnaryCall(
                    backendStubProvider.getChannel(),
                    AuditContract.DIRECT_LOG_ENTRY_METHOD,
                    CallOptions.DEFAULT.withDeadlineAfter(3, TimeUnit.SECONDS),
                    payload
            );

        }).exceptionally((_) -> {
            LOGGER.error("Direct audit failed for '{}' (uuid: {}) - falling back to memory buffer.", auditEntry.action(), auditEntry.playerId());
            if (!buffer.offer(auditEntry)) {
                LOGGER.error("Emergency buffer full: direct audit entry dropped for uuid: '{}'", auditEntry.playerId());
            }
            return null;
        });
    }

    private CompletableFuture<Void> triggerFlush(boolean drainAll) {
        if (buffer.isEmpty() || !isFlushing.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }

        final int limit = drainAll ? buffer.size() : drainAmountPerCycle;
        final var batch = new ArrayList<AuditEntry>(limit);
        buffer.drainTo(batch, limit);

        if (batch.isEmpty()) {
            isFlushing.set(false);
            return CompletableFuture.completedFuture(null);
        }

        final var serialized = batch.stream().map(this::toRawBson).toList();

        return logBatch(serialized)
                .exceptionally((_) -> {
                    LOGGER.warn("gRPC call to audit service failed — requeueing {} entries.", batch.size());
                    requeue(batch);
                    return null;
                })
                .whenComplete((_, _) -> isFlushing.set(false));
    }

    private void requeue(List<AuditEntry> batch) {
        int dropped = 0;

        for (AuditEntry entry : batch) {
            if (!buffer.offer(entry)) {
                dropped++;
            }
        }

        if (dropped > 0) {
            LOGGER.error("{} audit entries permanently dropped (buffer full).", dropped);
        }

        LOGGER.warn("{} audit entries successfully requeued.", batch.size() - dropped);
    }

    @Override
    protected <T> void handleGrpcExceptions(@NotNull final String actionName, @NotNull final StatusRuntimeException ex, final CompletableFuture<T> future) {
        switch (ex.getStatus().getCode()) {
            case UNAVAILABLE, DEADLINE_EXCEEDED ->
                    LOGGER.warn("Unable to reach back-end during '{}': {} (Code: {}). Actions will be retried.", actionName, ex.getMessage(), ex.getStatus().getCode());
            default ->
                    LOGGER.error("gRPC Status Exception during '{}': {} (Code: {})", actionName, ex.getMessage(), ex.getStatus().getCode());
        }
        if (future != null && !future.isDone()) {
            future.completeExceptionally(ex);
        }
    }

    @Override
    public void shutdown() {
        LOGGER.info("AuditManager shutdown initiated — draining remaining buffer...");
        scheduler.shutdown();

        if (!buffer.isEmpty()) {
            try {
                LOGGER.info("Flushing {} remaining audit entries asynchronously...", buffer.size());
                triggerFlush(true).get(6, TimeUnit.SECONDS);
            } catch (Exception e) {
                LOGGER.error("gRPC Status Exception during flushing audit entries.", e);
            }

        }

        executor.shutdown();

        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) scheduler.shutdownNow();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
            LOGGER.info("AuditManager shutdown complete. Buffer remaining: {} entries.", buffer.size());
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            LOGGER.error(e, "AuditManager shutdown interrupted — some entries may be lost.");
        }

    }

}
