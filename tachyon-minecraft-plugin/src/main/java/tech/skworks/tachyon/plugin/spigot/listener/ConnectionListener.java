package tech.skworks.tachyon.plugin.spigot.listener;

import org.bson.BsonDocument;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import tech.skworks.tachyon.api.audit.AuditEntry;
import tech.skworks.tachyon.api.profile.PlayerDataService;
import tech.skworks.tachyon.api.profile.TachyonProfile;
import tech.skworks.tachyon.api.profile.TachyonProfileRegistry;
import tech.skworks.tachyon.plugin.core.metric.scraper.VanillaMetrics;
import tech.skworks.tachyon.plugin.spigot.TachyonCore;
import tech.skworks.tachyon.plugin.common.util.TachyonLogger;

import java.util.UUID;

/**
 * Project Tachyon
 * Class ConnectionListener
 *
 * @author  Jimmy (vSKAH) - 06/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class ConnectionListener implements Listener {

    private final TachyonCore plugin;

    private static final TachyonLogger LOGGER = TachyonCore.getModuleLogger("ConnectionListener");

    public ConnectionListener(TachyonCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        if (!plugin.getHealthService().isHealthy()) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, "Tachyon back-end is currently unreachable ! Please contact an administrator");
        }

        final UUID uuid = event.getUniqueId();
        LOGGER.info("Processing pre-login for {} ({})", event.getName(), uuid);

        try {
            BsonDocument playerResponse = plugin.getPlayerDataService().tryPullProfile(uuid);

            if (playerResponse == null) {
                LOGGER.error("Profile load returned null for {} ({}) — kicking.", event.getName(), uuid);
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, plugin.getPluginConfig().playerDataConfig().dataLoadingKickMsg());
                return;
            }

            plugin.getTachyonProfileRegistry().buildProfile(playerResponse, uuid);
            if (plugin.getPluginConfig().auditConfig().enableLoginLogs())
                plugin.getAuditService().log(AuditEntry.of(uuid, "CONNECTION", "JOIN", "Player joined server"));
            LOGGER.info("Pre-login successful for {} ({}).", event.getName(), uuid);

        } catch (Exception e) {
            LOGGER.error(e, "Unexpected error during pre-login for {} ({})", event.getName(), uuid);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, "§c[Tachyon] An internal error occurred. Please reconnect.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLogin(PlayerLoginEvent event) {
        final PlayerLoginEvent.Result result = event.getResult();
        if (!event.getPlayer().hasPlayedBefore())
            VanillaMetrics.incrementTotalPlayers();

        if (result != PlayerLoginEvent.Result.ALLOWED) {
            final Player player = event.getPlayer();
            final UUID uuid = player.getUniqueId();
            final String name = player.getName();

            final TachyonProfileRegistry profilesRegistry = plugin.getTachyonProfileRegistry();

            if (profilesRegistry.profileIsLoaded(uuid)) {
                LOGGER.warn("Login denied for {} after profile was loaded (result: {}) — releasing state and unloading profile.", uuid, event.getResult());

                if (plugin.getPluginConfig().auditConfig().enableLoginLogs())
                    plugin.getAuditService().log(AuditEntry.of(uuid, "CONNECTION", "KICKED", "Login denied: " + event.getKickMessage()));

                profilesRegistry.unloadProfile(uuid, "LOGIN_DENIED");
                plugin.getPlayerSessionService().unlockPlayerProfile(uuid, name);
            }
            return;
        }


    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();
        final TachyonProfileRegistry profilesRegistry = plugin.getTachyonProfileRegistry();

        if (plugin.getPluginConfig().auditConfig().enableLogoutLogs())
            plugin.getAuditService().log(AuditEntry.of(uuid, "CONNECTION", "QUIT", "Player left server"));

        TachyonProfile profile = profilesRegistry.getProfile(uuid);

        if (profile == null) {
            LOGGER.error("Profile not found at disconnect for {} ({}) — cannot save.", player.getName(), uuid);
            return;
        }

        final PlayerDataService playerDataService = plugin.getPlayerDataService();
        LOGGER.info("Player {} ({}) disconnecting — saving dirty components...", player.getName(), uuid);

        playerDataService.pushProfile(profile).whenComplete((result, exception) -> {
            if (exception != null) {
                LOGGER.error("saveProfile() failed for {} ({}) at disconnect — data may be partially saved.", player.getName(), uuid);
            } else {
                LOGGER.info("saveProfile() confirmed for {} ({}).", player.getName(), uuid);
            }

            playerDataService.flushQueueForPlayer(uuid);
            profilesRegistry.unloadProfile(uuid, "DISCONNECT");
            plugin.getPlayerSessionService().unlockPlayerProfile(uuid, player.getName());
        });
    }

}
