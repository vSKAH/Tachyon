package tech.skworks.tachyon.plugin.core.system;

import io.grpc.CallOptions;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCalls;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tech.skworks.tachyon.api.services.SystemService;
import tech.skworks.tachyon.common.contract.SystemContract;
import tech.skworks.tachyon.common.marshaller.BsonMarshaller;
import tech.skworks.tachyon.plugin.core.grpc.BackendStubProvider;
import tech.skworks.tachyon.plugin.core.metric.scraper.TachyonMetrics;
import tech.skworks.tachyon.plugin.core.grpc.AbstractGrpcService;
import tech.skworks.tachyon.plugin.common.util.TachyonLogger;
import tech.skworks.tachyon.plugin.spigot.TachyonCore;

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
            return CompletableFuture.completedFuture(false);
        }

        final var sendTimestamp = System.currentTimeMillis();
        final var payload = BsonMarshaller.toRawBsonDocument(new BsonDocument().append("client_time", new BsonInt64(sendTimestamp)).append("server_name", new BsonString(serverName)));

        return asyncCall(executor, LOGGER, "pingBackend", () -> {

            var response = ClientCalls.blockingUnaryCall(backendStubProvider.getChannel(), SystemContract.PING_METHOD, CallOptions.DEFAULT.withDeadlineAfter(2, TimeUnit.SECONDS), payload);

            String backendName = response.getString("tachyon_server_name").getValue();
            LOGGER.info("Ping response in {}ms from {}", System.currentTimeMillis() - sendTimestamp, backendName);
            return true;

        }).whenComplete((_, _) -> pingInProgress.set(false))
                .exceptionally(ex -> {
                    LOGGER.warn("Ping to backend failed or timed out: {}", ex.getMessage());
                    return false;
                });
    }

    @Override
    protected <T> void handleGrpcExceptions(@NotNull final String actionName, @NotNull final StatusRuntimeException ex, final CompletableFuture<T> future) {

        switch (ex.getStatus().getCode()) {
            case UNAVAILABLE, DEADLINE_EXCEEDED ->
                    LOGGER.warn("Unable to reach back-end for ping: {} (Code: {}). Actions will be retried.", ex.getMessage(), ex.getStatus().getCode());
            default ->
                    LOGGER.error("gRPC Status Exception during ping: {} (Code: {})", ex.getMessage(), ex.getStatus().getCode());
        }
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
