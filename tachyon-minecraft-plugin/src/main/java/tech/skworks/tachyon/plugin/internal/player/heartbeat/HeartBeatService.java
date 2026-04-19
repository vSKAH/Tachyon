package tech.skworks.tachyon.plugin.internal.player.heartbeat;

import org.apache.logging.log4j.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import tech.skworks.tachyon.service.contracts.player.HeartBeatBatchRequest;
import tech.skworks.tachyon.service.contracts.player.PlayerRequest;
import tech.skworks.tachyon.plugin.spigot.TachyonCore;
import tech.skworks.tachyon.plugin.internal.GrpcClientManager;
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
public class HeartBeatService extends AbstractGrpcService {

    private static final TachyonLogger LOGGER = TachyonCore.getModuleLogger("PlayerProfileService");

    public HeartBeatService(@Nullable TachyonMetrics tachyonMetrics, GrpcClientManager grpcClientManager) {
        super(tachyonMetrics, grpcClientManager);
    }

    public void unlockPlayerProfile(UUID uuid, String playerName) {
        Thread.ofVirtual().name("tachyon-unlock-", 1).start(() -> {
            try (var _ = startTimer("FreePlayer")) {
                grpcClientManager.getPlayerStub(2)
                        .freePlayer(PlayerRequest.newBuilder().setUuid(uuid.toString()).build());

                LOGGER.info("Player state freed for {} ({}).", playerName, uuid);

            } catch (Exception e) {
                LOGGER.log(Level.WARN, e, "freePlayer() call failed for {} — state will expire via TTL (30s).", uuid);
                recordError("FreePlayer", e);
            }
        });
    }

    public void sendHeartBeats(boolean log) {
        if (Bukkit.getOnlinePlayers().isEmpty()) return;

        final List<PlayerRequest> beats = new ArrayList<>(Bukkit.getOnlinePlayers().size());
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            beats.add(PlayerRequest.newBuilder().setUuid(onlinePlayer.getUniqueId().toString()).build());
        }

        Thread.ofVirtual().name("tachyon-heartbeat-", 1).start(() -> {
            try (var _ = startTimer("PlayerHeartBeatBatch")) {
                HeartBeatBatchRequest request = HeartBeatBatchRequest.newBuilder().addAllBeat(beats).build();
                grpcClientManager.getPlayerStub(4).playerHeartBeatBatch(request);
                if (log) {
                    LOGGER.info("HeartBeats has been sent !");
                }
            } catch (Exception e) {
                LOGGER.error(e, "sendHeartBeats() call failed");
                recordError("PlayerHeartBeatBatch", e);
            }
        });
    }

}
