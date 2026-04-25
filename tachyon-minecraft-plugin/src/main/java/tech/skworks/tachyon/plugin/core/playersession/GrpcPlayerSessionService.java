package tech.skworks.tachyon.plugin.core.playersession;

import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.Nullable;
import tech.skworks.tachyon.api.profile.TachyonProfile;
import tech.skworks.tachyon.api.services.PlayerSessionService;
import tech.skworks.tachyon.libs.io.grpc.Status;
import tech.skworks.tachyon.libs.io.grpc.StatusRuntimeException;
import tech.skworks.tachyon.plugin.spigot.TachyonCore;
import tech.skworks.tachyon.plugin.core.grpc.BackendStubProvider;
import tech.skworks.tachyon.plugin.core.metric.scraper.TachyonMetrics;
import tech.skworks.tachyon.plugin.core.grpc.AbstractGrpcService;
import tech.skworks.tachyon.plugin.common.util.TachyonLogger;
import tech.skworks.tachyon.service.contracts.player.session.FreePlayerRequest;
import tech.skworks.tachyon.service.contracts.player.session.PlayerHeartBeatBatchRequest;

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
public class GrpcPlayerSessionService extends AbstractGrpcService implements PlayerSessionService {

    private static final TachyonLogger LOGGER = TachyonCore.getModuleLogger("PlayerProfileService");

    private final ExecutorService vThreadExecutor;
    private final ScheduledExecutorService scheduler;

    public GrpcPlayerSessionService(@Nullable TachyonMetrics tachyonMetrics, BackendStubProvider backendStubProvider, TachyonCore plugin) {
        super(tachyonMetrics, backendStubProvider);
        this.vThreadExecutor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("tachyon-sessions-vthread-", 1).factory());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("tachyon-session-scheduler").factory());
        int delay = plugin.getPluginConfig().playerSessionConfig().heartbeatSendDelay();
        this.scheduler.scheduleAtFixedRate(
                () -> this.sendHeartBeats(plugin.getTachyonProfileRegistry().getProfiles(), plugin.getPluginConfig().playerSessionConfig().logHeartbeatSend()),
                delay, delay, TimeUnit.SECONDS);
    }

    @Override
    public void unlockPlayerProfile(@NotNull final UUID uuid, @NotNull final String playerName) {
        asyncRun(vThreadExecutor, LOGGER, "FreePlayer", () -> backendStubProvider.getPlayerSessionStub(3).freePlayer(FreePlayerRequest.newBuilder().setUuid(uuid.toString()).build()));
    }

    @Override
    public void sendHeartBeats(@NotNull final Collection<TachyonProfile> profiles, boolean log) {
        if (profiles.isEmpty()) return;

        PlayerHeartBeatBatchRequest.Builder requestBuilder = PlayerHeartBeatBatchRequest.newBuilder();
        for (TachyonProfile player : profiles) {
            requestBuilder.addUuids(player.getUuid().toString());
        }
        final PlayerHeartBeatBatchRequest request = requestBuilder.build();

        asyncRun(vThreadExecutor, LOGGER, "PlayerHeartBeatBatch", () -> {
            backendStubProvider.getPlayerSessionStub(3).playerHeartBeatBatch(request);
            if (log) {
                LOGGER.info("HeartBeats has been sent !");
            }
        });
    }

    @Override
    protected <T> void handleGrpcExceptions(@NotNull final String actionName, @NotNull final StatusRuntimeException ex, final CompletableFuture<T> future) {
        final Status.Code code = ex.getStatus().getCode();
        final String description = ex.getStatus().getDescription();

        switch (code) {
            case UNAVAILABLE -> LOGGER.error("[gRPC] Backend is UNAVAILABLE during '{}'. Quarkus is down, restarting, or unreachable.", actionName);
            case DEADLINE_EXCEEDED -> LOGGER.warn("[gRPC] Timeout (DEADLINE_EXCEEDED) during '{}'. The network is lagging.", actionName);
            case INVALID_ARGUMENT -> LOGGER.error("[gRPC] Invalid payload sent during '{}'. Quarkus rejected the request: {}", actionName, description);
            case UNIMPLEMENTED -> LOGGER.error("[gRPC] Method not implemented on Quarkus during '{}'. Check your protobuf versions!", actionName);
            default -> LOGGER.error("[gRPC] Unexpected error [{}] during '{}': {}", code.name(), actionName, description);
        }

        if (future != null && !future.isDone()) {
            future.completeExceptionally(ex);
        }
    }


    @Override
    public void shutdown() {
        LOGGER.info("Shutting down PlayerSessionService...");
        scheduler.shutdown();
        vThreadExecutor.shutdown();
        try {
            if (!vThreadExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                LOGGER.warn("Session vThreadExecutor did not terminate within 10s — forcing shutdown.");
                vThreadExecutor.shutdownNow();
            }

            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warn("Session Scheduler did not terminate within 5s — forcing shutdown.");
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            vThreadExecutor.shutdownNow();
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

}
