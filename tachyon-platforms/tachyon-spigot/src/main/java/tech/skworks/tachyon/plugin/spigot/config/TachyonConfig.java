package tech.skworks.tachyon.plugin.spigot.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;
import tech.skworks.tachyon.core.audit.AuditConfig;
import tech.skworks.tachyon.core.grpc.GrpcClientConfig;
import tech.skworks.tachyon.core.metric.MetricsConfig;
import tech.skworks.tachyon.core.playerdata.PlayerDataConfig;
import tech.skworks.tachyon.core.playersession.PlayerSessionConfig;

import java.time.Duration;

/**
 * Project Tachyon
 * Class TachyonConfig
 *
 * @author  Jimmy (vSKAH) - 09/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public record TachyonConfig(@NotNull String serverName, @NotNull GrpcClientConfig grpcClientConfig,
                            @NotNull MetricsConfig metricsConfig, @NotNull AuditConfig auditConfig,
                            @NotNull PlayerSessionConfig playerSessionConfig,
                            @NotNull PlayerDataConfig playerDataConfig) {

    public static TachyonConfig fromFile(@NotNull final FileConfiguration configuration) {
        return new TachyonConfig(configuration.getString("server-name", "lobby-01"), loadClient(configuration),
                loadMetrics(configuration), loadAudit(configuration), loadSessions(configuration), loadPayerData(configuration));
    }

    private static PlayerDataConfig loadPayerData(@NotNull final FileConfiguration fileConfiguration) {
        ConfigurationSection session = fileConfiguration.getConfigurationSection("services.player-data");

        int dataLoadingMaxAttempts = session.getInt("load.attempts.max", 5);
        int dataLoadingCycleDelay = session.getInt("load.attempts.delay", 500);

        String dataLoadingKickMsg = session.getString("load.kick_msg", "[TACHYON] Data is loading");

        boolean enableDataAutoSave = session.getBoolean("auto-save.enabled", true);
        int dataAutoSaveDelay = session.getInt("auto-save.delay", 10);

        return new PlayerDataConfig(dataLoadingMaxAttempts, dataLoadingCycleDelay, dataLoadingKickMsg, enableDataAutoSave, dataAutoSaveDelay);
    }


    private static PlayerSessionConfig loadSessions(@NotNull final FileConfiguration fileConfiguration) {
        ConfigurationSection session = fileConfiguration.getConfigurationSection("services.session");
        int heartbeatSendDelay = session.getInt("heartbeat.send_delay", 5);
        boolean logHeartbeatSend = session.getBoolean("heartbeat.logs", true);

        return new PlayerSessionConfig(heartbeatSendDelay, logHeartbeatSend);
    }

    private static AuditConfig loadAudit(@NotNull final FileConfiguration fileConfiguration) {
        ConfigurationSection auditSection = fileConfiguration.getConfigurationSection("services.audit");
        int bufferSize = auditSection.getInt("buffer.size", 5000);
        int bufferFlushDelay = auditSection.getInt("buffer.flush_delay", 2);
        int bufferDrainPerCycle = auditSection.getInt("buffer.drain_per_cycle", 200);

        boolean enableLoginLogs = auditSection.getBoolean("logs.login.enabled", true);
        boolean enableLogoutLogs = auditSection.getBoolean("logs.logout.enabled", true);

        return new AuditConfig(bufferSize, bufferFlushDelay, bufferDrainPerCycle, enableLoginLogs, enableLogoutLogs);
    }

    private static GrpcClientConfig loadClient(@NotNull final FileConfiguration fileConfiguration) {
        final var config = new GrpcClientConfig();
        final ConfigurationSection section = fileConfiguration.getConfigurationSection("grpc");
        if (section == null) {
            return config;
        }

        final String target = section.getString("target", null);
        if (target != null && !target.isBlank()) {
            config.setTarget(target);
        } else {
            config.setAddress(section.getString("host", "127.0.0.1"), section.getInt("port", 9000));
        }

        config.useTLS(section.getBoolean("use-tls", false));

        final long keepAliveTimeSec = section.getLong("keep-alive.time", 30L);
        final long keepAliveTimeoutSec = section.getLong("keep-alive.timeout", 5L);
        final boolean keepAliveWithoutCalls = section.getBoolean("keep-alive.without-calls", true);
        config.setKeepAlive(Duration.ofSeconds(keepAliveTimeSec), Duration.ofSeconds(keepAliveTimeoutSec), keepAliveWithoutCalls);

        final int maxInboundMb = section.getInt("max-inbound-message-size-mb", 32);
        config.setMaxInboundMessageSize(maxInboundMb * 1024 * 1024);

        final String lbPolicy = section.getString("load-balancing-policy", "round_robin");
        config.setLoadBalancingPolicy(lbPolicy);

        final boolean useVirtualThreads = section.getBoolean("use-virtual-threads", true);
        config.setUseVirtualThreads(useVirtualThreads);

        final boolean useToken = section.getBoolean("authentication.use-bearer-token", false);
        final String token = section.getString("authentication.bearer-token", "PLEASE CHANGE ME");

        if (useToken)
            config.setBearerToken(token);

        return config;
    }

    private static MetricsConfig loadMetrics(@NotNull final FileConfiguration configuration) {
        return new MetricsConfig(configuration.getString("metrics.host", "0.0.0.0"),
                configuration.getInt("metrics.port", 9001), configuration.getString("metrics.token", null));
    }

}
