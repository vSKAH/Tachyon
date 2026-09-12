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
                nextSave = currentInFlight.handle((_, _) -> null).thenCompose(_ -> executePush(tachyonProfile));
            }

            nextSave.whenComplete((_, _) -> inFlightSaves.remove(uuid, nextSave));
            return nextSave;
        });
    }

    private CompletableFuture<Void> executePush(@NotNull final TachyonProfile tachyonProfile) {
        if (!tachyonProfile.hasPendingChanges()) {
            return CompletableFuture.completedFuture(null);
        }

        final var uuid = tachyonProfile.getUuid();
        final PushPayload payload = buildPushDocument(tachyonProfile);

        if (payload.snapshotVersions.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        return asyncRun(vThreadExecutor, LOGGER, "SaveProfile", () -> {
            ClientCalls.blockingUnaryCall(
                    grpcClient.getChannel(),
                    PlayerDataContract.PUSH_PROFILE_METHOD,
                    CallOptions.DEFAULT.withDeadlineAfter(2, TimeUnit.SECONDS),
                    payload.document
            );
        }).whenComplete((_, throwable) -> {
            if (throwable == null) {
                tachyonProfile.markAsClean(payload.snapshotVersions(), payload.deletedNames());
            } else {
                LOGGER.warn("Save profile failed for {} ({}) — routing to local recovery store.", uuid, throwable.getMessage());
                //TODO: DLQ
            }
        });
    }

    private record PushPayload(RawBsonDocument document, Map<ComponentNamespace, Long> snapshotVersions, List<ComponentNamespace> deletedNames) {}

    private @NotNull PushPayload buildPushDocument(@NotNull final TachyonProfile tachyonProfile) {
        final var uuid = tachyonProfile.getUuid();
        final var strUuid = uuid.toString();

        final var rootDocument = new BsonDocument("uuid", new BsonString(strUuid));
        final var saveDocument = new BsonDocument();
        final var versionsDocument = new BsonDocument();
        final var snapshotVersions = new HashMap<ComponentNamespace, Long>();

        final var dirty = Map.copyOf(tachyonProfile.extractDirtyComponents());
        dirty.forEach((namespace, document) -> {
            final var namespaceKey = namespace.toString();
            final var version = tachyonProfile.getVersion(namespace);

            saveDocument.append(namespaceKey, document);
            versionsDocument.append(namespaceKey, new BsonInt64(version));
            snapshotVersions.put(namespace, version);
        });
        rootDocument.append("save", saveDocument);

        final var removeArray = new BsonArray();
        final var deleted = tachyonProfile.extractDeletedComponents();

        deleted.forEach(name -> {
            final var namespaceKey = name.fullNamespace();
            long version = tachyonProfile.getVersion(name);

            removeArray.add(new BsonString(namespaceKey));
            versionsDocument.append(namespaceKey, new BsonInt64(version));
            snapshotVersions.put(name, version);
        });

        rootDocument.append("remove", removeArray);
        rootDocument.append("versions", versionsDocument);


        return new PushPayload(BsonUtil.toRawBsonDocument(rootDocument), snapshotVersions, deleted);
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
