package tech.skworks.tachyon.plugin.internal.audit;

import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNull;
import tech.skworks.tachyon.api.services.AuditService;
import tech.skworks.tachyon.service.contracts.audit.LogBatchRequest;
import tech.skworks.tachyon.service.contracts.audit.LogRequest;
import tech.skworks.tachyon.plugin.spigot.TachyonCore;
import tech.skworks.tachyon.plugin.internal.util.TachyonLogger;
import tech.skworks.tachyon.plugin.internal.GrpcClientManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Project Tachyon
 * Class AuditManager
 *
 * @author  Jimmy (vSKAH) - 06/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class GrpcAuditService implements AuditService {
    private static final TachyonLogger LOGGER = TachyonCore.getModuleLogger("AuditManager");

    private final GrpcClientManager grpcClientManager;
    private final String serverName;

    private final BlockingQueue<LogRequest> buffer = new LinkedBlockingQueue<>(50000);

    private final ScheduledExecutorService scheduler;
    private final ExecutorService flushExecutor;

    public GrpcAuditService(GrpcClientManager grpcClientManager, String serverName) {
        this.grpcClientManager = grpcClientManager;
        this.serverName        = serverName;
        this.flushExecutor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("tachyon-audit-emergency-flush-", 1).factory());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("tachyon-audit-scheduler-", 1).factory());
        this.scheduler.scheduleAtFixedRate(this::flush, 2, 2, TimeUnit.SECONDS);
        LOGGER.info("AuditManager initialized for server '{}' — buffer capacity: 50000, flush interval: 2s.", serverName);
    }

    /**
     * Enqueues an audit log entry.
     * Thread-safe. If the buffer is full, an async emergency flush is triggered
     * and the entry is dropped (logged as SEVERE).
     */
    @Override
    public void log(@NotNull final String uuid, @NotNull final String action, @NotNull final String details) {
        LogRequest entry = LogRequest.newBuilder()
                .setUuid(uuid)
                .setModule(serverName)
                .setAction(action)
                .setDetails(details)
                .setTimestampMs(System.currentTimeMillis())
                .build();

        boolean accepted = buffer.offer(entry);
        if (!accepted) {
            LOGGER.error("Audit buffer full (50000 entries) — entry dropped. Triggering emergency flush. ");
            LOGGER.error("Action: '{}' for uuid: {}", action, uuid);
            flushExecutor.submit(this::flush);
        }
    }

    /**
     * send 300 logs and if it failed requeue it.
     * On failure, entries are returned to the buffer (best effort).
     *
     * NOTE: This method is blocking — never call it from the main thread directly.
     */
    private void flush() {
        if (buffer.isEmpty()) return;

        List<LogRequest> batch = new ArrayList<>();
        buffer.drainTo(batch, 400);
        if (batch.isEmpty()) return;

        try {
            grpcClientManager.getAuditStub(1).logEventBatch(LogBatchRequest.newBuilder().addAllLogs(batch).build());
        } catch (Exception e) {
            LOGGER.log(Level.WARN, e, "gRPC call to audit service failed — requeueing {} entries.", batch.size());
            requeue(batch);
        }
    }

    private void requeue(List<LogRequest> batch) {
        int requeued = 0;
        int dropped  = 0;
        for (LogRequest entry : batch) {
            if (buffer.offer(entry)) requeued++;
            else dropped++;
        }
        if (dropped > 0) {
            LOGGER.error("{} audit entries permanently dropped (buffer still full after flush failure).", dropped);
        }
        if (requeued > 0) {
            LOGGER.warn("{} audit entries re-queued for next flush cycle.", requeued);
        }
    }

    public void shutdown() {
        LOGGER.info("AuditManager shutdown initiated — draining remaining buffer...");

        scheduler.shutdown();
        flushExecutor.shutdown();

        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                LOGGER.error("Audit scheduler did not terminate within 10s — forcing shutdown.");
                scheduler.shutdownNow();
            }
            if (!flushExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.error("Audit flush executor did not terminate within 5s — forcing shutdown.");
                flushExecutor.shutdownNow();
            }
            flush();
            LOGGER.info("AuditManager shutdown complete. Buffer remaining: {} entries.", buffer.size());
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            flushExecutor.shutdownNow();
            Thread.currentThread().interrupt();
            LOGGER.error(e, "AuditManager shutdown interrupted — some entries may be lost.");
        }
    }
}
