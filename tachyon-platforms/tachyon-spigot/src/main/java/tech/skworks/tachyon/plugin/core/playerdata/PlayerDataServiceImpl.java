package tech.skworks.tachyon.plugin.core.playerdata;

import io.grpc.CallOptions;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCalls;
import org.bson.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tech.skworks.tachyon.api.component.ComponentNamespace;
import tech.skworks.tachyon.api.profile.PlayerDataService;
import tech.skworks.tachyon.api.profile.TachyonProfile;
import tech.skworks.tachyon.common.BsonUtil;
import tech.skworks.tachyon.common.contract.PlayerDataContract;
import tech.skworks.tachyon.plugin.spigot.TachyonCore;
import tech.skworks.tachyon.plugin.core.grpc.AbstractGrpcService;
import tech.skworks.tachyon.plugin.core.util.TachyonLogger;
import tech.skworks.tachyon.plugin.core.grpc.GrpcClient;
import tech.skworks.tachyon.plugin.core.metric.scraper.TachyonMetrics;
import tech.skworks.tachyon.plugin.core.resilience.ResilienceService;

import java.util.*;
import java.util.concurrent.*;

/**
 * Project Tachyon
 * Class ComponentService
 *
 * @author  Jimmy (vSKAH) - 07/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class PlayerDataServiceImpl extends AbstractGrpcService implements PlayerDataService {

    private static final TachyonLogger LOGGER = TachyonCore.getModuleLogger("ComponentService");
    private static final int MAX_PULL_ATTEMPTS = 5;
    private static final long RETRY_BACKOFF_MS = 500L;

    private final ExecutorService vThreadExecutor;
    private final Map<UUID, CompletableFuture<Void>> inFlightSaves;

    public PlayerDataServiceImpl(final @NotNull ResilienceService resilience, final @Nullable TachyonMetrics tachyonMetrics, final @NotNull GrpcClient grpcClient) {
        super("player-data", resilience, tachyonMetrics, grpcClient);

        this.vThreadExecutor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("tachyon-playerdata-executor-", 1).factory());
        this.inFlightSaves = new ConcurrentHashMap<>();
    }


    //TODO: set all deadline grpc in configuration

    @Override
    public @Nullable BsonDocument tryPullProfile(@NotNull final UUID uuid) {
        final RawBsonDocument rawRequest = BsonUtil.toRawBsonDocument(new BsonDocument("uuid", new BsonString(uuid.toString())));

        try (var _ = startProfileLoadTimer()) {
            for (int attempt = 1; attempt <= MAX_PULL_ATTEMPTS; attempt++) {
                try {
                    try (var _ = startTimer("PullProfile")) {
                        return ClientCalls.blockingUnaryCall(
                                grpcClient.getChannel(),
                                PlayerDataContract.PULL_PROFILE_METHOD,
                                CallOptions.DEFAULT.withDeadlineAfter(4, TimeUnit.SECONDS),
                                rawRequest
                        );
                    }

                } catch (StatusRuntimeException e) {
                    Status status = e.getStatus();

                    if (status.getCode() == Status.Code.ABORTED) {
                        String reason = status.getDescription() != null ? status.getDescription() : "LOCKED";
                        LOGGER.info("tryPullProfile() retry {}/{} for {} — reason: {}", attempt, MAX_PULL_ATTEMPTS, uuid, reason);
                        recordPlayerLockRetry();

                        if (attempt == MAX_PULL_ATTEMPTS) {
                            LOGGER.error("tryPullProfile() exhausted {} attempts for {}. Player remains locked.", MAX_PULL_ATTEMPTS, uuid);
                            recordPlayerLockExhausted();
                            recordError("PullProfile", "PLAYER_LOCKED");
                            return null;
                        }

                        if (!sleepInterruptible(RETRY_BACKOFF_MS, uuid)) return null;
                        continue;
                    }

                    if (status.getCode() == Status.Code.UNAVAILABLE && attempt < MAX_PULL_ATTEMPTS) {
                        String reason = status.getDescription() != null ? status.getDescription() : "UNAVAILABLE";
                        LOGGER.warn("tryPullProfile() backend unavailable (attempt {}/{}) for {} — reason: '{}'", attempt, MAX_PULL_ATTEMPTS, uuid, reason);
                        if (!sleepInterruptible(RETRY_BACKOFF_MS / 2, uuid)) return null;
                        continue;
                    }

                    LOGGER.error("tryPullProfile() gRPC failure for {}: {} ({})", uuid, status.getCode(), status.getDescription());
                    recordError("PullProfile", status.getCode().name());
                    return null;

                } catch (Exception e) {
                    LOGGER.error(e, "tryPullProfile() unexpected fatal failure for {}", uuid);
                    recordError("PullProfile", "FATAL");
                    return null;
                }
            }
        }

        return null;
    }

    private boolean sleepInterruptible(long millis, UUID uuid) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.error("tryPullProfile() interrupted while waiting for {}", uuid);
            return false;
        }
    }

    private TachyonMetrics.MetricTimer startProfileLoadTimer() {
        return tachyonMetrics != null ? tachyonMetrics.startProfileLoadTimer() : () -> {
        };
    }

    private void recordPlayerLockRetry() {
        if (tachyonMetrics != null) {
            tachyonMetrics.recordPlayerLockRetry();
        }
    }

    private void recordPlayerLockExhausted() {
        if (tachyonMetrics != null) {
            tachyonMetrics.recordPlayerLockExhausted();
        }
    }

    /**
     * Root Document :
     * -> Save Document "save" :
     *      NameSpaceKey : Data
     * -> Version document "versions" :
     *      NameSpaceKey : Int64 version
     * -> Remove Array "remove" :
     *      NameSpaceKey
     */

    private BsonDocument buildPushDocument(@NotNull final TachyonProfile tachyonProfile) {
        final UUID uuid = tachyonProfile.getUuid();
        final String strUuid = uuid.toString();

        final BsonDocument rootDocument = new BsonDocument("uuid", new BsonString(strUuid));

        final BsonDocument saveDocument = new BsonDocument();
        final BsonDocument versionsDocument = new BsonDocument();

        Map<ComponentNamespace, BsonDocument> dirty = Map.copyOf(tachyonProfile.extractDirtyComponents());
        dirty.forEach((namespace, document) -> {
            final var namespaceKey = namespace.toString();
            saveDocument.append(namespaceKey, document);
            versionsDocument.append(namespaceKey, new BsonInt64(tachyonProfile.getVersion(namespace)));
        });
        rootDocument.append("save", saveDocument);
        rootDocument.append("versions", versionsDocument);

        BsonArray removeArray = new BsonArray();
        tachyonProfile.extractDeletedComponents().forEach(name -> {
            removeArray.add(new BsonString(name.toString()));
            versionsDocument.append(name.toString(), new BsonInt64(tachyonProfile.getVersion(name)));
        });
        rootDocument.append("remove", removeArray);

        return rootDocument;
    }

    public @NotNull CompletableFuture<Void> pushProfile(@NotNull final TachyonProfile tachyonProfile) {
        final var uuid = tachyonProfile.getUuid();

        if (!tachyonProfile.hasPendingChanges()) {
            CompletableFuture<Void> existing = inFlightSaves.get(uuid);
            return existing != null ? existing : CompletableFuture.completedFuture(null);
        }

        return inFlightSaves.compute(uuid, (_, currentInFlight) -> {
            CompletableFuture<Void> nextSave;
            if (currentInFlight == null || currentInFlight.isDone()) {
                nextSave = executePush(tachyonProfile);
            } else {
                nextSave = currentInFlight.handle((_, _) -> null)
                        .thenCompose(_ -> executePush(tachyonProfile));
            }

            nextSave.whenComplete((_, _) -> inFlightSaves.remove(uuid, nextSave));
            return nextSave;
        });
    }

    private CompletableFuture<Void> executePush(@NotNull final TachyonProfile tachyonProfile) {
        if (!tachyonProfile.hasPendingChanges()) {
            return CompletableFuture.completedFuture(null);
        }

        final UUID uuid = tachyonProfile.getUuid();
        final RawBsonDocument document = BsonUtil.toRawBsonDocument(buildPushDocument(tachyonProfile));

        return asyncRun(vThreadExecutor, LOGGER, "SaveProfile", () -> {
            ClientCalls.blockingUnaryCall(
                    grpcClient.getChannel(),
                    PlayerDataContract.PUSH_PROFILE_METHOD,
                    CallOptions.DEFAULT.withDeadlineAfter(2, TimeUnit.SECONDS),
                    document
            );
        }).exceptionally(throwable -> {
            LOGGER.warn("Save profile failed for {} ({}) — routing to local recovery store.", uuid, throwable.getMessage());
            //TODO: DLQ
            return null;
        });
    }

    @Override
    protected <T> void handleGrpcExceptions(@NotNull final String actionName, @NotNull final StatusRuntimeException ex, final CompletableFuture<T> future) {
        if (future != null && !future.isDone()) {
            future.completeExceptionally(ex);
        }
    }


    @Override
    public void shutdown() {
        LOGGER.info("Shutdown initiated: stopping schedulers and processing pending queues...");

        vThreadExecutor.shutdown();

        try {
            if (!vThreadExecutor.awaitTermination(15, TimeUnit.SECONDS)) {
                LOGGER.warn("vThreadExecutor did not terminate within 15s — forcing shutdown.");
                vThreadExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            vThreadExecutor.shutdownNow();
            Thread.currentThread().interrupt();
            LOGGER.error(e, "Shutdown interrupted while waiting for network tasks.");
        }


        LOGGER.info("Shutdown complete.");
    }
}
