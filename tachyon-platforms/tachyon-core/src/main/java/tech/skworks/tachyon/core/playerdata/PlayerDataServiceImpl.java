package tech.skworks.tachyon.core.playerdata;

import io.grpc.CallOptions;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCalls;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.RawBsonDocument;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.skworks.tachyon.api.profile.PlayerDataService;
import tech.skworks.tachyon.api.profile.TachyonProfile;
import tech.skworks.tachyon.common.BsonUtil;
import tech.skworks.tachyon.common.contract.PlayerDataContract;
import tech.skworks.tachyon.core.grpc.AbstractGrpcService;
import tech.skworks.tachyon.core.grpc.GrpcClient;
import tech.skworks.tachyon.core.metric.scraper.TachyonCollector;
import tech.skworks.tachyon.core.resilience.ResilienceService;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Short class explaination
 * <p>
 * Long class explaination with @link if needed
 * </p>
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @author Jimmy (vSKAH) - 12/09/2026
 * @version 1.0
 * @since 2.0.0-alpha.2
 */
public class PlayerDataServiceImpl extends AbstractGrpcService implements PlayerDataService {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tachyon/PlaterDataService");
    private static final int MAX_PULL_ATTEMPTS = 5;
    private static final long RETRY_BACKOFF_MS = 500L;

    private final ExecutorService vThreadExecutor;
    private final Map<UUID, CompletableFuture<Void>> inFlightSaves;
    private final PlayerDataDlqService dlqService;

    public PlayerDataServiceImpl(
            @NotNull final String serverId,
            @NotNull final ResilienceService resilience,
            @NotNull final GrpcClient grpcClient,
            @NotNull final PlayerDataDlqService dlqService,
            @Nullable final TachyonCollector tachyonCollector
    ) {
        super(serverId, "player-data", resilience, grpcClient, tachyonCollector);

        this.vThreadExecutor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("tachyon-playerdata-executor-", 1).factory());
        this.inFlightSaves = new ConcurrentHashMap<>();
        this.dlqService = Objects.requireNonNull(dlqService, "dlqService cannot be null");
    }

    @Override
    public @Nullable BsonDocument tryPullProfile(@NotNull final UUID uuid) {
        Objects.requireNonNull(uuid, "uuid cannot be null");
        final RawBsonDocument rawRequest = BsonUtil.toRawBsonDocument(new BsonDocument("uuid", new BsonString(uuid.toString())).append("server_id", new BsonString(getServerId())));

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
                    final var status = e.getStatus();
                    final var statusCode = status.getCode();

                    if (statusCode == Status.Code.ABORTED) {
                        final var reason = status.getDescription() != null ? status.getDescription() : "LOCKED";
                        LOGGER.info("tryPullProfile() retry {}/{} for {} — reason: {}", attempt, MAX_PULL_ATTEMPTS, uuid, reason);
                        recordPlayerLockRetry();

                        if (attempt == MAX_PULL_ATTEMPTS) {
                            LOGGER.error("tryPullProfile() exhausted {} attempts for {}. Player remains locked.", MAX_PULL_ATTEMPTS, uuid);
                            recordPlayerLockExhausted();
                            recordError("PullProfile", "PLAYER_LOCKED");
                            return null;
                        }

                        if (!sleep(RETRY_BACKOFF_MS, uuid)) {
                            return null;
                        }
                        continue;
                    }

                    if (statusCode == Status.Code.UNAVAILABLE && attempt < MAX_PULL_ATTEMPTS) {
                        String reason = status.getDescription() != null ? status.getDescription() : "UNAVAILABLE";
                        LOGGER.warn("tryPullProfile() backend unavailable (attempt {}/{}) for {} — reason: '{}'", attempt, MAX_PULL_ATTEMPTS, uuid, reason);
                        if (!sleep(RETRY_BACKOFF_MS / 2, uuid)) {
                            return null;
                        }
                        continue;
                    }

                    LOGGER.error("tryPullProfile() gRPC failure for {}: {} ({})", uuid, statusCode, status.getDescription());
                    recordError("PullProfile", statusCode.name());
                    return null;
                } catch (Exception e) {
                    LOGGER.error("tryPullProfile() unexpected fatal failure for {}", uuid, e);
                    recordError("PullProfile", "FATAL");
                    return null;
                }
            }
        }

        return null;
    }

    private boolean sleep(final long millis, final UUID uuid) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.error("tryPullProfile() interrupted while waiting for {}", uuid);
            return false;
        }
    }

    private TachyonCollector.MetricTimer startProfileLoadTimer() {
        return tachyonCollector != null ? tachyonCollector.startProfileLoadTimer() : () -> {
        };
    }

    private void recordPlayerLockRetry() {
        if (tachyonCollector != null) {
            tachyonCollector.recordPlayerLockRetry();
        }
    }

    private void recordPlayerLockExhausted() {
        if (tachyonCollector != null) {
            tachyonCollector.recordPlayerLockExhausted();
        }
    }

    @Override
    public @NotNull CompletableFuture<Void> pushProfile(@NotNull final TachyonProfile tachyonProfile) {
        Objects.requireNonNull(tachyonProfile, "tachyonProfile cannot be null");
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
        final var dirtyComponents = tachyonProfile.extractDirtyComponents();
        final var deletedComponents = tachyonProfile.extractDeletedComponents();
        final var delta = PlayerDeltaAdapter.fromProfile(tachyonProfile);

        if (delta.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        final var versionSnapshots = new java.util.HashMap<tech.skworks.tachyon.api.component.ComponentNamespace, Long>();
        for (final var namespace : dirtyComponents.keySet()) {
            versionSnapshots.put(namespace, tachyonProfile.getVersion(namespace));
        }

        return asyncRun(vThreadExecutor, LOGGER, "SaveProfile", () -> {
            ClientCalls.blockingUnaryCall(
                    grpcClient.getChannel(),
                    PlayerDataContract.PUSH_PROFILE_METHOD,
                    CallOptions.DEFAULT.withDeadlineAfter(2, TimeUnit.SECONDS),
                    delta.toRawBsonDocument()
            );
        }).whenComplete((_, throwable) -> {
            if (throwable == null) {
                tachyonProfile.markAsClean(versionSnapshots, deletedComponents);
            } else {
                LOGGER.warn("Save profile failed for {} ({}) — routing to local recovery store.", uuid, throwable.getMessage());
                dlqService.enqueue(delta);
            }
        });
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
            LOGGER.error("Shutdown interrupted while waiting for network tasks.", e);
        }

        LOGGER.info("Shutdown complete.");
    }
}
