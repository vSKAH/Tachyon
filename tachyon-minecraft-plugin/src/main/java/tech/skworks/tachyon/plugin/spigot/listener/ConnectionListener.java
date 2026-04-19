package tech.skworks.tachyon.plugin.spigot.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import tech.skworks.tachyon.api.profile.TachyonProfile;
import tech.skworks.tachyon.service.contracts.player.PlayerResponse;
import tech.skworks.tachyon.plugin.spigot.TachyonCore;
import tech.skworks.tachyon.plugin.internal.audit.GrpcAuditService;
import tech.skworks.tachyon.plugin.internal.player.ProfileManager;
import tech.skworks.tachyon.plugin.internal.player.component.ComponentService;
import tech.skworks.tachyon.plugin.internal.player.heartbeat.HeartBeatService;
import tech.skworks.tachyon.plugin.internal.util.TachyonLogger;

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
    private final ProfileManager profileManager;
    private final GrpcAuditService audit;
    private final ComponentService componentService;
    private final HeartBeatService heartBeatService;
    private static final TachyonLogger LOGGER = TachyonCore.getModuleLogger("ConnectionListener");

    public ConnectionListener(ProfileManager profileManager, HeartBeatService profileService, GrpcAuditService audit, ComponentService componentService) {
        this.profileManager = profileManager;
        this.audit = audit;
        this.componentService = componentService;
        this.heartBeatService = profileService;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;

        final UUID uuid = event.getUniqueId();
        LOGGER.info("Processing pre-login for {} ({})", event.getName(), uuid);

        try {
            PlayerResponse playerResponse = componentService.loadProfile(uuid);

            if (playerResponse == null) {
                LOGGER.error("Profile load returned null for {} ({}) — kicking.", event.getName(), uuid);
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, "§c[Tachyon] Failed to load your data. Please reconnect.");
                return;
            }

            profileManager.load(playerResponse, uuid);
            audit.log(uuid.toString(), "JOIN", "Player joined server");
            LOGGER.info("Pre-login successful for {} ({}).", event.getName(), uuid);

        }
        catch (Exception e) {
            LOGGER.error(e, "Unexpected error during pre-login for {} ({})", event.getName(), uuid);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, "§c[Tachyon] An internal error occurred. Please reconnect.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onLogin(PlayerLoginEvent event) {
        if (event.getResult() != PlayerLoginEvent.Result.ALLOWED) {
            final Player player = event.getPlayer();
            final UUID uuid = player.getUniqueId();
            final String name = player.getName();

            if (profileManager.isLoaded(uuid)) {
                LOGGER.warn("Login denied for {} after profile was loaded (result: {}) — releasing state and unloading profile.", uuid, event.getResult());
                audit.log(uuid.toString(), "KICKED", "Login denied: " + event.getKickMessage());

                profileManager.unloadPlayer(uuid);
                heartBeatService.unlockPlayerProfile(uuid, name);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        final Player player = event.getPlayer();
        final UUID   uuid   = player.getUniqueId();

        audit.log(uuid.toString(), "QUIT", "Player left server");

        TachyonProfile profile = profileManager.get(uuid);

        if (profile == null) {
            LOGGER.error("Profile not found at disconnect for {} ({}) — cannot save.", player.getName(), uuid);
            return;
        }

        LOGGER.info("Player {} ({}) disconnecting — saving dirty components...", player.getName(), uuid);

        profile.saveProfile().whenComplete((result, exception) -> {
            if (exception != null) {
                LOGGER.error(exception, "saveProfile() failed for {} ({}) at disconnect — data may be partially saved.", player.getName(), uuid);
            } else {
                LOGGER.info("saveProfile() confirmed for {} ({}).", player.getName(), uuid);
            }

            componentService.flushQueueForPlayer(uuid);
            profileManager.unloadPlayer(uuid);
            heartBeatService.unlockPlayerProfile(uuid, player.getName());
        });
    }

}
