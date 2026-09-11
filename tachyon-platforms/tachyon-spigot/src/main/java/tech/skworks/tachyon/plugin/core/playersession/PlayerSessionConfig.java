package tech.skworks.tachyon.plugin.core.playersession;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;

/**
 * Project Tachyon
 * Class PlayerSessionConfig
 *
 * @author  Jimmy (vSKAH) - 24/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public record PlayerSessionConfig(int heartbeatSendDelay, boolean logHeartbeatSend) {

    public static PlayerSessionConfig fromFile(@NotNull final FileConfiguration fileConfiguration) {
        ConfigurationSection session = fileConfiguration.getConfigurationSection("services.session");
        int heartbeatSendDelay = session.getInt("heartbeat.send_delay", 5);
        boolean logHeartbeatSend = session.getBoolean("heartbeat.logs", true);

        return new PlayerSessionConfig(heartbeatSendDelay, logHeartbeatSend);
    }

}