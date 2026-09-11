package tech.skworks.tachyon.plugin.core.grpc;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.grpc.StatusRuntimeException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tech.skworks.tachyon.plugin.core.metric.scraper.TachyonMetrics;
import tech.skworks.tachyon.plugin.core.resilience.ResilienceService;
import tech.skworks.tachyon.plugin.core.util.TachyonLogger;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Project Tachyon
 * Class AbstractGrpcService
 *
 * @author  Jimmy (vSKAH) - 13/04/2026
 * @version 2.0
 * @since 1.0.0-SNAPSHOT
 */
public abstract class AbstractGrpcService {

    protected final @NotNull String serviceId;
    protected final @NotNull ResilienceService resilience;
    protected final @NotNull GrpcClient grpcClient;
    protected final @Nullable TachyonMetrics tachyonMetrics;

    public AbstractGrpcService(@NotNull final String serviceId, @NotNull final ResilienceService resilience, @Nullable final TachyonMetrics tachyonMetrics, @NotNull final GrpcClient grpcClient) {
        this.serviceId = Objects.requireNonNull(serviceId, "serviceId cannot be null");
        this.resilience = Objects.requireNonNull(resilience, "resilience cannot be null");
        this.grpcClient = Objects.requireNonNull(grpcClient, "grpcClient cannot be null");
        this.tachyonMetrics = tachyonMetrics;
    }

    public @NotNull String getServiceId() {
        return serviceId;
    }

    public @NotNull ResilienceService getResilience() {
        return resilience;
    }

    public @NotNull GrpcClient getGrpcClient() {
        return grpcClient;
    }

    public boolean isAvailable() {
        return resilience.isAvailable(serviceId);
    }

    public TachyonMetrics.MetricTimer startTimer(@NotNull final String method) {
        return tachyonMetrics != null ? tachyonMetrics.startGrpcTimer(method) : () -> {
        };
    }

    public void recordError(@NotNull final String method, @NotNull final String errorCode) {
        if (tachyonMetrics != null) {
            tachyonMetrics.recordGrpcError(method, errorCode);
        }
    }
    protected <T> void handleGrpcExceptions(@NotNull final String actionName, @NotNull final StatusRuntimeException ex, final CompletableFuture<T> future) {
        if (future != null && !future.isDone()) {
            future.completeExceptionally(ex);
        }
    }

    protected <T> CompletableFuture<T> asyncCall(@NotNull final Executor executor, @NotNull final TachyonLogger logger, @NotNull final String actionName, @NotNull final Supplier<T> grpcCall) {
        final CompletableFuture<T> future = new CompletableFuture<>();

        executor.execute(() -> {
            try (var _ = startTimer(actionName)) {
                T result = "health".equalsIgnoreCase(serviceId) ? grpcCall.get() : resilience.execute(serviceId, grpcCall::get);
                future.complete(result);
            } catch (CallNotPermittedException ex) {
                logger.warn("gRPC call '{}' rejected: CircuitBreaker '{}' is OPEN.", actionName, serviceId);
                future.completeExceptionally(ex);
            } catch (StatusRuntimeException ex) {
                handleGrpcExceptions(actionName, ex, future);
            } catch (Exception ex) {
                logger.error(ex, "Client-side execution failure during action '{}'", actionName);
                if (!future.isDone()) {
                    future.completeExceptionally(ex);
                }
            }
        });

        return future;
    }

    protected CompletableFuture<Void> asyncRun(@NotNull final Executor executor, @NotNull final TachyonLogger logger, @NotNull final String actionName, @NotNull final Runnable grpcCall) {
        return asyncCall(executor, logger, actionName, () -> {
            grpcCall.run();
            return null;
        });
    }

    public abstract void shutdown();
}
