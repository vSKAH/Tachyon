package tech.skworks.tachyon.core.playersession;

import io.grpc.CallOptions;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCalls;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.skworks.tachyon.api.profile.TachyonProfile;
import tech.skworks.tachyon.api.profile.TachyonProfileRegistry;
import tech.skworks.tachyon.api.services.PlayerSessionService;
import tech.skworks.tachyon.common.BsonUtil;
import tech.skworks.tachyon.common.contract.PlayerSessionContract;
import tech.skworks.tachyon.core.grpc.AbstractGrpcService;
import tech.skworks.tachyon.core.grpc.GrpcClient;
import tech.skworks.tachyon.core.metric.scraper.TachyonCollector;
import tech.skworks.tachyon.core.resilience.ResilienceService;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Project Tachyon
 * Class PlayerProfileService
 *
 * @author  Jimmy (vSKAH) - 13/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class PlayerSessionServiceImpl extends AbstractGrpcService implements PlayerSessionService {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tachyon/PlayerProfileService");

    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;

    public PlayerSessionServiceImpl(@NotNull final ResilienceService resilience,
                                    @NotNull final GrpcClient grpcClient,
                                    @NotNull final PlayerSessionConfig playerSessionConfig,
                                    @NotNull final TachyonProfileRegistry tachyonProfileRegistry,
                                    @Nullable final TachyonCollector tachyonCollector) {
        super("player-session", resilience, grpcClient, tachyonCollector);
        this.executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("tachyon-sessions-vthread-", 1).factory());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("tachyon-session-scheduler").factory());
        final var delay = playerSessionConfig.heartbeatSendDelay();
        this.scheduler.scheduleAtFixedRate(
                () -> this.sendHeartBeats(tachyonProfileRegistry.getProfiles(), playerSessionConfig.logHeartbeatSend()),
                delay, delay, TimeUnit.SECONDS);
    }

    @Override
    public void unlockPlayerProfile(@NotNull final UUID uuid, @NotNull final String playerName) {
        final var payload = BsonUtil.toRawBsonDocument(new BsonDocument("uuid", new BsonString(uuid.toString())));
        asyncRun(executor, LOGGER, "FreePlayer", () -> ClientCalls.blockingUnaryCall(
                grpcClient.getChannel(),
                PlayerSessionContract.FREE_PLAYER_METHOD,
                CallOptions.DEFAULT.withDeadlineAfter(3, TimeUnit.SECONDS),
                payload
        )).exceptionally(throwable -> {
            LOGGER.error("Failed to unlock player {}", playerName, throwable);
            return null;
        }).thenRun(() -> LOGGER.info("Unlocked player {}", playerName));
    }

    @Override
    public void sendHeartBeats(@NotNull final Collection<TachyonProfile> profiles, boolean log) {
        if (profiles.isEmpty()) return;

        final var uuidsArray = new BsonArray();
        for (TachyonProfile player : profiles) {
            uuidsArray.add(new BsonString(player.getUuid().toString()));
        }

        final var payload = BsonUtil.toRawBsonDocument(new BsonDocument("uuids", new BsonArray(uuidsArray)));

        asyncRun(executor, LOGGER, "PlayerHeartBeatBatch", () -> ClientCalls.blockingUnaryCall(
                grpcClient.getChannel(),
                PlayerSessionContract.HEARTBEAT_BATCH_METHOD,
                CallOptions.DEFAULT.withDeadlineAfter(3, TimeUnit.SECONDS),
                payload
        )).exceptionally(_ -> {
            LOGGER.error("Unable to send heartbeat batch");
            return null;
        }).thenRun(() -> {
            if (log) {
                LOGGER.info("HeartBeats has been sent !");
            }
        });
    }

    @Override
    protected <T> void handleGrpcExceptions(@NotNull final String actionName, @NotNull final StatusRuntimeException ex, @NotNull final CompletableFuture<T> future) {
        final Status.Code code = ex.getStatus().getCode();
        final String description = ex.getStatus().getDescription();

        switch (code) {
            case UNAVAILABLE ->
                    LOGGER.error("[gRPC] Backend is UNAVAILABLE during '{}'. Quarkus is down, restarting, or unreachable.", actionName);
            case DEADLINE_EXCEEDED ->
                    LOGGER.warn("[gRPC] Timeout (DEADLINE_EXCEEDED) during '{}'. The network is lagging.", actionName);
            case INVALID_ARGUMENT ->
                    LOGGER.error("[gRPC] Invalid payload sent during '{}'. Quarkus rejected the request: {}", actionName, description);
            case UNIMPLEMENTED ->
                    //TODO: edit that
                    LOGGER.error("[gRPC] Method not implemented on Quarkus during '{}'. Check your protobuf versions!", actionName);
            default ->
                    LOGGER.error("[gRPC] Unexpected error [{}] during '{}': {}", code.name(), actionName, description);
        }

        super.handleGrpcExceptions(actionName, ex, future);
    }


    @Override
    public void shutdown() {
        LOGGER.info("Shutting down PlayerSessionService...");
        scheduler.shutdown();
        executor.shutdown();

        try {
            if (!scheduler.awaitTermination(4, TimeUnit.SECONDS)) {
                LOGGER.warn("Session Scheduler did not terminate within 4s — forcing shutdown.");
                scheduler.shutdownNow();
            }

            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warn("Session vThreadExecutor did not terminate within 5s — forcing shutdown.");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

}
