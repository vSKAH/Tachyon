package tech.skworks.tachyon.plugin.internal.snapshots;

import com.github.luben.zstd.Zstd;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tech.skworks.tachyon.api.services.SnapshotService;
import tech.skworks.tachyon.libs.io.grpc.Status;
import tech.skworks.tachyon.libs.io.grpc.StatusRuntimeException;
import tech.skworks.tachyon.service.contracts.snapshot.*;
import tech.skworks.tachyon.libs.com.google.protobuf.ByteString;
import tech.skworks.tachyon.libs.com.google.protobuf.Message;
import tech.skworks.tachyon.plugin.spigot.TachyonCore;
import tech.skworks.tachyon.plugin.internal.GrpcClientManager;
import tech.skworks.tachyon.plugin.internal.metric.scraper.TachyonMetrics;
import tech.skworks.tachyon.plugin.internal.util.AbstractGrpcService;
import tech.skworks.tachyon.plugin.internal.util.TachyonLogger;

import java.util.concurrent.*;

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

    @Override
    protected <T> void handleGrpcExceptions(String actionName, StatusRuntimeException ex, CompletableFuture<T> future) {
        final Status.Code code = ex.getStatus().getCode();
        final String description = ex.getStatus().getDescription();

        switch (code) {
            case NOT_FOUND:
            case INVALID_ARGUMENT:
            case ABORTED:
                LOGGER.warn("Action '{}' rejected by backend (Code logic): {}", actionName, description);
                break;

            case DATA_LOSS:
            case UNAVAILABLE:
            case INTERNAL:
                LOGGER.error(ex, "Critical infrastructure failure during action '{}': {}", actionName, description);
                break;

            default:
                LOGGER.error(ex, "Unexpected backend error during action '{}'", actionName);
                break;
        }

        recordError(actionName, ex);
        future.completeExceptionally(ex);
    }


    public CompletableFuture<Void> takeDatabaseSnapshot(@NotNull final String playerUniqueId, @NotNull final String reason, @NotNull SnapshotTriggerType triggerType) {
        return asyncRun(executor, LOGGER, "takeDatabaseSnapshot", () -> {
            TakeDatabaseSnapshotRequest request = TakeDatabaseSnapshotRequest.newBuilder().setPlayerId(playerUniqueId).setReason(reason).setTriggerType(triggerType).build();
            grpcClientManager.getSnapshotStub(3).takeDatabaseSnapshot(request);
        });
    }

    @Override
    public <T extends Message> CompletableFuture<Void> takeComponentSnapshot(@NotNull final String playerUniqueId, @NotNull final String reason,
                                                                             @NotNull final SnapshotTriggerType triggerType, @NotNull final T component) {
        return asyncRun(executor, LOGGER, "takeComponentSnapshot", () -> {
            TakeComponentSnapshotRequest request = TakeComponentSnapshotRequest.newBuilder().setPlayerId(playerUniqueId).setReason(reason)
                    .setTriggerType(triggerType).setTargetComponent(component.getDescriptorForType().getFullName())
                    .setRawData(ByteString.copyFrom(Zstd.compress(component.toByteArray()))).build();
            grpcClientManager.getSnapshotStub(3).takeComponentSnapshot(request);
        });

    }

    @Override
    public CompletableFuture<ToggleLockSnapshotResponse> toggleSnapshotLocking(@NotNull final String snapshotId, @NotNull final String executorUniqueId) {
        return asyncCall(executor, LOGGER, "toggleLockSnapshot", () -> {
            ToggleLockSnapshotRequest request = ToggleLockSnapshotRequest.newBuilder().setSnapshotId(snapshotId).setLockerId(executorUniqueId).build();
            return grpcClientManager.getSnapshotStub(3).toggleLockSnapshot(request);
        });
    }

    @Override
    public CompletableFuture<ListSnapshotsResponse> getSnapshots(@NotNull final String playerUniqueId) {
        return asyncCall(executor, LOGGER, "getSnapshots", () -> {
            ListSnapshotsRequest request = ListSnapshotsRequest.newBuilder().setPlayerId(playerUniqueId).build();
            return grpcClientManager.getSnapshotStub(3).listSnapshots(request);
        });
    }

    public CompletableFuture<DecodeSnapshotResponse> decodeSnapshot(@NotNull final String snapshotId) {
        return asyncCall(executor, LOGGER, "decodeSnapshot", () -> {
            DecodeSnapshotRequest request = DecodeSnapshotRequest.newBuilder().setSnapshotId(snapshotId).build();
            return grpcClientManager.getSnapshotStub(3).decodeSnapshot(request);
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
