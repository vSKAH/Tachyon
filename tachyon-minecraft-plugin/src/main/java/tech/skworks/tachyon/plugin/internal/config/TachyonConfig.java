package tech.skworks.tachyon.plugin.internal.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Project Tachyon
 * Class TachyonConfig
 *
 * @author  Jimmy (vSKAH) - 09/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public record TachyonConfig(String serverName, String grpcHost, int GrpcPort, String metricsHost, int metricsPort) {

    public static TachyonConfig fromFile(FileConfiguration configuration) {
        return new TachyonConfig(configuration.getString("server-name", "lobby-01"), configuration.getString("grpc.host", "0.0.0.0"), configuration.getInt("grpc.port", 9000), configuration.getString("metrics.host", "0.0.0.0"), configuration.getInt("metrics.port"));
    }

}
