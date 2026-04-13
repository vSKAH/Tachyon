package tech.skworks.tachyon.plugin.internal.player.profile;

import org.apache.logging.log4j.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import tech.skworks.tachyon.contracts.common.StandardResponse;
import tech.skworks.tachyon.contracts.player.HeartBeatBatchRequest;
import tech.skworks.tachyon.contracts.player.PlayerRequest;
import tech.skworks.tachyon.plugin.Plugin;
import tech.skworks.tachyon.plugin.internal.grpc.GrpcClientManager;
import tech.skworks.tachyon.plugin.internal.metric.scraper.TachyonMetrics;
import tech.skworks.tachyon.plugin.internal.util.AbstractGrpcService;
import tech.skworks.tachyon.plugin.internal.util.TachyonLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Project Tachyon
 * Class PlayerProfileService
 *
 * @author  Jimmy (vSKAH) - 13/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class PlayerProfileService extends AbstractGrpcService {

    private static final TachyonLogger LOGGER = Plugin.getModuleLogger("PlayerProfileService");

    public PlayerProfileService(@Nullable TachyonMetrics tachyonMetrics, GrpcClientManager grpcClientManager) {
        super(tachyonMetrics, grpcClientManager);

    }

    public void unlockPlayerProfile(UUID uuid, String playerName) {
        try (var time = startTimer("FreePlayer")) {
            StandardResponse freeResp = grpcClientManager.getPlayerStub(2).freePlayer(PlayerRequest.newBuilder().setUuid(uuid.toString()).build());
            if (!freeResp.getSuccess()) {
                LOGGER.warn("freePlayer() returned success=false for {} — state may expire via TTL.", uuid);
            } else {
                LOGGER.info("Player state freed for {} ({}).", playerName, uuid);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARN, e, "freePlayer() call failed for {} — state will expire via TTL (30s).", uuid);
            recordError("FreePlayer", e);
        }
    }

    public List<PlayerRequest> buildHeartBeats() {
        if (Bukkit.getOnlinePlayers().isEmpty()) return List.of();

        final List<PlayerRequest> beats = new ArrayList<>(Bukkit.getOnlinePlayers().size());
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            beats.add(PlayerRequest.newBuilder().setUuid(onlinePlayer.getUniqueId().toString()).build());
        }
        return beats;
    }

    public void sendHeartBeats(List<PlayerRequest> heartbeats) {
        Thread.startVirtualThread(() -> {
            try (var t = startTimer("PlayerHeartBeatBatch")) {
                HeartBeatBatchRequest request = HeartBeatBatchRequest.newBuilder().addAllBeat(heartbeats).build();
                StandardResponse resp = grpcClientManager.getPlayerStub(3).playerHeartBeatBatch(request);
                if (!resp.getSuccess()) {
                    LOGGER.warn("sendHeartBeats() returned success=false - message {}", resp.getMessage());
                } else {
                    LOGGER.info("HeartBeats has been sent !");
                }
            } catch (Exception e) {
                LOGGER.error( e, "sendHeartBeats() call failed");
                recordError("PlayerHeartBeatBatch", e);
            }
        });
    }

}
