package tech.skworks.tachyon.plugin.core.metric;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Project Tachyon
 * Class MetricsConfiguration
 *
 * @author  Jimmy (vSKAH) - 24/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public record MetricsConfig(String metricsHost, int metricsPort) {

    public static MetricsConfig fromFile(FileConfiguration configuration) {
        return new MetricsConfig(configuration.getString("metrics.host", "0.0.0.0"), configuration.getInt("metrics.port"));
    }

}
