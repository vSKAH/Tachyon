package tech.skworks.tachyon.plugin.spigot.config;

import org.bukkit.configuration.file.FileConfiguration;
import tech.skworks.tachyon.plugin.core.audit.AuditConfig;
import tech.skworks.tachyon.plugin.core.metric.MetricsConfig;
import tech.skworks.tachyon.plugin.core.playerdata.PlayerDataConfig;
import tech.skworks.tachyon.plugin.core.playersession.PlayerSessionConfig;

/**
 * Project Tachyon
 * Class TachyonConfig
 *
 * @author  Jimmy (vSKAH) - 09/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public record TachyonConfig(String serverName, String grpcHost, int grpcPort,
                            MetricsConfig metricsConfig, AuditConfig auditConfig,
                            PlayerSessionConfig playerSessionConfig, PlayerDataConfig playerDataConfig) {

    public static TachyonConfig fromFile(FileConfiguration configuration) {
        return new TachyonConfig(configuration.getString("server-name", "lobby-01"),
                configuration.getString("grpc.host", "0.0.0.0"),
                configuration.getInt("grpc.port", 9000),
                MetricsConfig.fromFile(configuration),
                AuditConfig.fromFile(configuration),
                PlayerSessionConfig.fromFile(configuration),
                PlayerDataConfig.fromFile(configuration));
    }

}
