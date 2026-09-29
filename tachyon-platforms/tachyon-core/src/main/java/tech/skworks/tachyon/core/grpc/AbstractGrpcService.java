package tech.skworks.tachyon.core.grpc;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.grpc.StatusRuntimeException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import tech.skworks.tachyon.core.metric.scraper.TachyonCollector;
import tech.skworks.tachyon.core.resilience.ResilienceService;

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

    protected final @NotNull String serverId;
    protected final @NotNull String serviceId;
    protected final @NotNull ResilienceService resilience;
    protected final @NotNull GrpcClient grpcClient;
    protected final @Nullable TachyonCollector tachyonCollector;

    public AbstractGrpcService(@NotNull final String serverId, @NotNull final String serviceId, @NotNull final ResilienceService resilience, @NotNull final GrpcClient grpcClient, @Nullable final TachyonCollector tachyonCollector) {
        this.serverId = serverId;
        this.serviceId = Objects.requireNonNull(serviceId, "serviceId cannot be null");
        this.resilience = Objects.requireNonNull(resilience, "resilience cannot be null");
        this.grpcClient = Objects.requireNonNull(grpcClient, "grpcClient cannot be null");
        this.tachyonCollector = tachyonCollector;
    }

    public @NotNull String getServerId() {
        return serverId;
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

    public TachyonCollector.MetricTimer startTimer(@NotNull final String method) {
        return tachyonCollector != null ? tachyonCollector.startGrpcTimer(method) : () -> {
        };
    }

    public void recordError(@NotNull final String method, @NotNull final String errorCode) {
        if (tachyonCollector != null) {
            tachyonCollector.recordGrpcError(method, errorCode);
        }
    }
    protected <T> void handleGrpcExceptions(@NotNull final String actionName, @NotNull final StatusRuntimeException ex, @NotNull final CompletableFuture<T> future) {
        if (!future.isDone()) {
            future.completeExceptionally(ex);
        }
    }

    protected <T> CompletableFuture<T> asyncCall(@NotNull final Executor executor, @NotNull final Logger logger, @NotNull final String actionName, @NotNull final Supplier<T> grpcCall) {
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
                logger.error("Client-side execution failure during action '{}'", actionName, ex);
                if (!future.isDone()) {
                    future.completeExceptionally(ex);
                }
            }
        });

        return future;
    }

    protected CompletableFuture<Void> asyncRun(@NotNull final Executor executor, @NotNull final Logger logger, @NotNull final String actionName, @NotNull final Runnable grpcCall) {
        return asyncCall(executor, logger, actionName, () -> {
            grpcCall.run();
            return null;
        });
    }

    public abstract void shutdown();
}
