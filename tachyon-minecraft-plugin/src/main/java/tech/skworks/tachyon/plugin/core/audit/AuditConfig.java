package tech.skworks.tachyon.plugin.core.audit;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;

/**
 * Project Tachyon
 * Class AuditConfig
 *
 * @author  Jimmy (vSKAH) - 24/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public record AuditConfig(int bufferSize, int bufferFlushDelay, int bufferDrainPerCycles,
                          boolean enableLoginLogs, boolean enableLogoutLogs) {

    public static AuditConfig fromFile(@NotNull final FileConfiguration fileConfiguration) {
        ConfigurationSection auditSection = fileConfiguration.getConfigurationSection("services.audit");
        int bufferSize = auditSection.getInt("buffer.size", 5000);
        int bufferFlushDelay = auditSection.getInt("buffer.flush_delay", 2);
        int bufferDrainPerCycle = auditSection.getInt("buffer.drain_per_cycle", 200);

        boolean enableLoginLogs = auditSection.getBoolean("logs.login.enabled", true);
        boolean enableLogoutLogs = auditSection.getBoolean("logs.logout.enabled", true);

        return new AuditConfig(bufferSize, bufferFlushDelay, bufferDrainPerCycle, enableLoginLogs, enableLogoutLogs);
    }

}
