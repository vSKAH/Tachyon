package tech.skworks.tachyon.plugin.internal.snapshots;

import com.github.luben.zstd.Zstd;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tech.skworks.tachyon.api.services.SnapshotService;
import tech.skworks.tachyon.service.contracts.snapshot.*;
import tech.skworks.tachyon.libs.com.google.protobuf.ByteString;
import tech.skworks.tachyon.libs.com.google.protobuf.Message;
import tech.skworks.tachyon.plugin.spigot.TachyonCore;
import tech.skworks.tachyon.plugin.internal.GrpcClientManager;
import tech.skworks.tachyon.plugin.internal.metric.scraper.TachyonMetrics;
import tech.skworks.tachyon.plugin.internal.util.AbstractGrpcService;
import tech.skworks.tachyon.plugin.internal.util.TachyonLogger;

import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Project Tachyon
 * Class SnapshotManaer
 *
 * @author  Jimmy (vSKAH) - 17/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class GrpcSnapshotService extends AbstractGrpcService implements SnapshotService {

    private static final TachyonLogger LOGGER = TachyonCore.getModuleLogger("SnapshotService");
    private final ExecutorService executor;

    public GrpcSnapshotService(@Nullable TachyonMetrics tachyonMetrics, GrpcClientManager grpcClientManager) {
        super(tachyonMetrics, grpcClientManager);
        this.executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("snapshot-vthread-", 1).factory());
    }

    //TODO: gérer les logs
    private <T> CompletableFuture<T> asyncCall(Supplier<T> grpcCall) {
        CompletableFuture<T> future = new CompletableFuture<>();

        executor.execute(() -> {
            try {
                T result = grpcCall.get();
                future.complete(result);
            } catch (Throwable ex) {
                future.completeExceptionally(ex);
            }
        });

        return future;
    }

    private CompletableFuture<Void> asyncRun(Runnable grpcCall) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        executor.execute(() -> {
            try {
                grpcCall.run();
                future.complete(null);
            } catch (Throwable ex) {
                future.completeExceptionally(ex);
            }
        });

        return future;
    }

    public CompletableFuture<Void> takeDatabaseSnapshot(@NotNull final String playerUniqueId, @NotNull final String reason, @NotNull SnapshotTriggerType triggerType) {
        return asyncRun(() -> {
            SnapshotRequest request = SnapshotRequest.newBuilder().setUuid(playerUniqueId).setReason(reason).setTriggerType(triggerType).build();
            grpcClientManager.getSnapshotStub(3).createSnapshot(request);
        });
    }

    @Override
    public <T extends Message> CompletableFuture<Void> takeComponentSnapshot(@NotNull final String playerUniqueId, @NotNull final String reason,
                                                                             @NotNull final SnapshotTriggerType triggerType, @NotNull final T component) {
        return asyncRun(() -> {
            SpecificSnapshotRequest request = SpecificSnapshotRequest.newBuilder().setUuid(playerUniqueId).setReason(reason)
                    .setTriggerType(triggerType).setTargetComponent(component.getDescriptorForType().getFullName())
                    .setRawData(ByteString.copyFrom(Zstd.compress(component.toByteArray()))).build();
            grpcClientManager.getSnapshotStub(3).createSpecificSnapshot(request);
        });

    }

    @Override
    public CompletableFuture<SnapshotListResponse> getSnapshots(@NotNull final String playerUniqueId) {
        return asyncCall(() -> {
            SnapshotListRequest request = SnapshotListRequest.newBuilder().setUuid(playerUniqueId).build();
            return grpcClientManager.getSnapshotStub(3).listSnapshots(request);
        });
    }

    public CompletableFuture<DecodeSnapshotResponse> decodeSnapshot(@NotNull final String snapshotId) {
        return asyncCall(() -> {
            ViewSnapshotRequest request = ViewSnapshotRequest.newBuilder().setSnapshotId(snapshotId).build();
            return grpcClientManager.getSnapshotStub(3).viewSnapshot(request);
        });
    }

    public void shutdown() {
        LOGGER.info("Shutdown initiated — draining remaining buffer...");

        executor.shutdown();

        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                LOGGER.error("Snapshot executor did not terminate within 10s — forcing shutdown.");
                executor.shutdownNow();
            }
            LOGGER.info("Shutdown complete.");
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            LOGGER.error(e, "Shutdown interrupted — some data may be lost.");
        }
    }
}
