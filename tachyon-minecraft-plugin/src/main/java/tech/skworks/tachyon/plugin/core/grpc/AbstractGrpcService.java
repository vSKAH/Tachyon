package tech.skworks.tachyon.plugin.core.grpc;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tech.skworks.tachyon.libs.io.grpc.StatusRuntimeException;
import tech.skworks.tachyon.plugin.common.util.TachyonLogger;
import tech.skworks.tachyon.plugin.core.metric.scraper.TachyonMetrics;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;


/**
 * Project Tachyon
 * Class AbstractGrpcService
 *
 * @author  Jimmy (vSKAH) - 13/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public abstract class AbstractGrpcService {

    protected final @Nullable TachyonMetrics tachyonMetrics;
    protected final @NotNull BackendStubProvider backendStubProvider;

    public AbstractGrpcService(@Nullable TachyonMetrics tachyonMetrics, @NotNull BackendStubProvider backendStubProvider) {
        this.tachyonMetrics = tachyonMetrics;
        this.backendStubProvider = backendStubProvider;
    }

    public AutoCloseable startTimer(String method) {
        return tachyonMetrics != null ? tachyonMetrics.startGrpcTimer(method) : () -> {};
    }

    public void recordError(String method, Exception e) {
        if (tachyonMetrics == null) return;
        String label = e instanceof StatusRuntimeException ex
                ? ex.getStatus().getCode().name()
                : e.getClass().getSimpleName();
        tachyonMetrics.recordGrpcError(method, label);
    }

    public void recordError(String method, String errorCode) {
        if (tachyonMetrics != null) tachyonMetrics.recordGrpcError(method, errorCode);
    }

    protected abstract <T> void handleGrpcExceptions(@NotNull final String actionName, @NotNull final StatusRuntimeException ex, final CompletableFuture<T> future);
    //todo: add dlq method

    protected <T> CompletableFuture<T> asyncCall(Executor executor, TachyonLogger logger, String actionName, Supplier<T> grpcCall) {
        CompletableFuture<T> future = new CompletableFuture<>();

        executor.execute(() -> {
            try (var _ = startTimer(actionName)) {
                T result = grpcCall.get();
                future.complete(result);
            } catch (StatusRuntimeException ex) {
                handleGrpcExceptions(actionName, ex, future);
                recordError(actionName, ex);
            } catch (Exception ex) {
                logger.error(ex, "Client-side execution failure during action '{}'", actionName);
                recordError(actionName + "_JVM", ex);
                future.completeExceptionally(ex);
            }
        });

        return future.orTimeout(4, TimeUnit.SECONDS);
    }

    protected CompletableFuture<Void> asyncRun(Executor executor, TachyonLogger logger, String actionName, Runnable grpcCall) {
        return asyncCall(executor, logger, actionName, () -> {
            grpcCall.run();
            return null;
        });
    }


    public abstract void shutdown();

}
