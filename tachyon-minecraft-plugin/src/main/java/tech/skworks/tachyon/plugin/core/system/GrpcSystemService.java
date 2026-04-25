package tech.skworks.tachyon.plugin.core.system;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tech.skworks.tachyon.api.services.SystemService;
import tech.skworks.tachyon.libs.io.grpc.StatusRuntimeException;
import tech.skworks.tachyon.plugin.core.grpc.BackendStubProvider;
import tech.skworks.tachyon.plugin.core.metric.scraper.TachyonMetrics;
import tech.skworks.tachyon.plugin.core.grpc.AbstractGrpcService;
import tech.skworks.tachyon.plugin.common.util.TachyonLogger;
import tech.skworks.tachyon.plugin.spigot.TachyonCore;
import tech.skworks.tachyon.service.contracts.system.PingRequest;
import tech.skworks.tachyon.service.contracts.system.PingResponse;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Project Tachyon
 * Class GrpcSystemService
 *
 * @author  Jimmy (vSKAH) - 21/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class GrpcSystemService extends AbstractGrpcService implements SystemService {

    private static final TachyonLogger LOGGER = TachyonCore.getModuleLogger("SystemService");
    private final String serverName;
    private final ExecutorService executor;
    private final AtomicBoolean pingInProgress;

    public GrpcSystemService(@Nullable TachyonMetrics tachyonMetrics, @NotNull BackendStubProvider backendStubProvider, @NotNull final String serverName) {
        super(tachyonMetrics, backendStubProvider);
        this.serverName = serverName;
        this.executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("tachyon-system-vthread-", 1).factory());
        this.pingInProgress = new AtomicBoolean(false);
    }

    @Override
    public CompletableFuture<Boolean> pingBackend() {

        if (!pingInProgress.compareAndSet(false, true)) {
            LOGGER.warn("A ping is already in progress. Skipping this request.");
            return CompletableFuture.completedFuture(false);
        }

        return asyncCall(
                executor,
                LOGGER,
                "pingBackend",
                () -> {
                    long send = System.currentTimeMillis();

                    PingResponse response = backendStubProvider.getSystemStub(4).ping(PingRequest.newBuilder().setClientTime(send).setSpigotServerName(serverName).build());
                    if (response != null) {
                        long time = response.getServerTime() - send;
                        LOGGER.info("The ping took {}ms to respond ({})", time, response.getTachyonServerName());
                        return true;
                    }
                    return false;
                }
        ).whenComplete((result, throwable) -> pingInProgress.set(false))
                .exceptionally(ex -> {
                    LOGGER.warn("Ping failed or timed out: {}", ex.getMessage());
                    return false;
                });
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
        LOGGER.info("Shutting down GrpcSystemService...");
        executor.shutdown();

        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.error("System executor did not terminate within 5s — forcing shutdown.");
                executor.shutdownNow();
            }
            LOGGER.info("Shutdown complete.");
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            LOGGER.error(e, "Shutdown interrupted.");
        }
    }

}
