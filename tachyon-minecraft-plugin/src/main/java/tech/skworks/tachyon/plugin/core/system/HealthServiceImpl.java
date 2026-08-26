package tech.skworks.tachyon.plugin.core.system;

import io.grpc.CallOptions;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCalls;
import org.bson.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tech.skworks.tachyon.api.system.PingResponse;
import tech.skworks.tachyon.api.system.HealthService;
import tech.skworks.tachyon.common.contract.HealthContract;
import tech.skworks.tachyon.common.marshaller.BsonMarshaller;
import tech.skworks.tachyon.plugin.common.util.TachyonLogger;
import tech.skworks.tachyon.plugin.core.grpc.AbstractGrpcService;
import tech.skworks.tachyon.plugin.core.grpc.BackendStubProvider;
import tech.skworks.tachyon.plugin.core.metric.scraper.TachyonMetrics;
import tech.skworks.tachyon.plugin.spigot.TachyonCore;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Project Tachyon
 * Class GrpcSystemService
 *
 * <p>Handles core backend connectivity, gRPC ping telemetry, and autonomous adaptive health monitoring.</p>
 *
 * @author  Jimmy (vSKAH)
 * @version 2.0
 * @since 1.0.0-SNAPSHOT
 */
public class HealthServiceImpl extends AbstractGrpcService implements HealthService {

    private static final TachyonLogger LOGGER = TachyonCore.getModuleLogger("HealthService");
    private static final PingResponse OFFLINE_FALLBACK = new PingResponse(-1, -1, "OFFLINE", false, false, false);

    private static final long HEALTHY_INTERVAL_MS = 8000L;
    private static final long UNHEALTHY_INTERVAL_MS = 4000L;

    private final String serverName;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean pingInProgress;
    private final AtomicBoolean monitoring;

    private volatile boolean healthy = true;

    public HealthServiceImpl(@Nullable TachyonMetrics tachyonMetrics,
                             @NotNull BackendStubProvider backendStubProvider,
                             @NotNull final String serverName) {
        super(tachyonMetrics, backendStubProvider);
        this.serverName = serverName;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("tachyon-health-monitor-", 1).factory());
        this.pingInProgress = new AtomicBoolean(false);
        this.monitoring = new AtomicBoolean(false);
    }

    public void startHealthMonitoring() {
        if (!monitoring.compareAndSet(false, true)) return;
        scheduleNextProbe(HEALTHY_INTERVAL_MS);
    }

    @Override
    public boolean isHealthy() {
        return healthy;
    }

    @Override
    public CompletableFuture<PingResponse> pingBackend() {
        if (!pingInProgress.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }

        final long sendTime = System.currentTimeMillis();
        final var payload = BsonMarshaller.toRawBsonDocument(new BsonDocument()
                .append("client_time", new BsonInt64(sendTime))
                .append("server_name", new BsonString(serverName)));

        return asyncCall(scheduler, LOGGER, "pingBackend", () -> {
            var rawResponse = ClientCalls.blockingUnaryCall(
                    backendStubProvider.getChannel(),
                    HealthContract.PING_METHOD,
                    CallOptions.DEFAULT.withDeadlineAfter(2, TimeUnit.SECONDS),
                    payload
            );

            final long receiveTime = System.currentTimeMillis();

            var typedResponse = new PingResponse(
                    rawResponse.getInt64("client_time", new BsonInt64(sendTime)).getValue(),
                    rawResponse.getInt64("server_time", new BsonInt64(-1)).getValue(),
                    rawResponse.getString("tachyon_server_name", new BsonString("UNKNOWN")).getValue(),
                    rawResponse.getBoolean("redis_online", new BsonBoolean(false)).getValue(),
                    rawResponse.getBoolean("mongo_online", new BsonBoolean(false)).getValue(),
                    rawResponse.getBoolean("healthy", new BsonBoolean(false)).getValue()
            );

            long rtt = typedResponse.roundTripLatencyMs(receiveTime);
            long drift = typedResponse.clockDriftMs(receiveTime);

            if (typedResponse.healthy()) {
                LOGGER.info("Ping in {}ms (Drift: {}ms) from '{}' [Mongo: UP, Redis: UP]",
                        rtt, drift, typedResponse.tachyonServerName());
            } else {
                LOGGER.warn("Ping in {}ms from '{}' — UNHEALTHY [Mongo: {}, Redis: {}]",
                        rtt, typedResponse.tachyonServerName(),
                        typedResponse.mongoOnline() ? "UP" : "DOWN",
                        typedResponse.redisOnline() ? "UP" : "DOWN");
            }

            if (Math.abs(drift) > 2000) {
                LOGGER.warn("Significant clock drift ({}ms) detected with backend node '{}'. Check NTP synchronization.",
                        drift, typedResponse.tachyonServerName());
            }

            return typedResponse;

        }).exceptionally(ex -> {
            LOGGER.warn("Ping to backend failed or timed out: {}", ex.getMessage());
            return OFFLINE_FALLBACK;
        }).whenComplete((_, _) -> pingInProgress.set(false));
    }

    private void runHealthProbe() {
        if (!monitoring.get()) return;

        pingBackend().whenComplete((response, ex) -> {
            boolean isUp = (ex == null && response != null && response.healthy());
            updateHealthStatus(isUp);
        });
    }

    private void updateHealthStatus(boolean isUp) {
        if (!monitoring.get()) return;

        if (isUp) {
            if (!healthy) {
                LOGGER.info("Quarkus Backend is back ONLINE! Resuming normal 8s polling.");
                healthy = true;
                //TODO: Add dlq flush
            }
            scheduleNextProbe(HEALTHY_INTERVAL_MS);
        } else {
            if (healthy) {
                LOGGER.error("Quarkus Backend is UNREACHABLE! Switching to fast 4s polling.");
                healthy = false;
            }
            scheduleNextProbe(UNHEALTHY_INTERVAL_MS);
        }
    }

    private void scheduleNextProbe(long delayMillis) {
        if (monitoring.get() && !scheduler.isShutdown()) {
            scheduler.schedule(this::runHealthProbe, delayMillis, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    protected <T> void handleGrpcExceptions(@NotNull final String actionName, @NotNull final StatusRuntimeException ex, final CompletableFuture<T> future) {
        LOGGER.warn("[gRPC] {} during '{}'", ex.getStatus(), actionName);
        if (future != null && !future.isDone()) {
            future.completeExceptionally(ex);
        }
    }

    @Override
    public void shutdown() {
        LOGGER.info("Shutting down GrpcSystemService...");
        monitoring.set(false);
        scheduler.shutdown();

        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warn("System scheduler did not terminate within 5s — forcing shutdownNow().");
                scheduler.shutdownNow();
            }
            LOGGER.info("GrpcSystemService shutdown complete.");
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
            LOGGER.error(e, "GrpcSystemService shutdown interrupted.");
        }
    }
}
