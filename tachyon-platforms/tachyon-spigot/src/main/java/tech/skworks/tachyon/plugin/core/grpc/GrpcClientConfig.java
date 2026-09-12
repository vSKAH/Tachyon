package tech.skworks.tachyon.plugin.core.grpc;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;

/**
 * Short class explaination
 * <p>
 * Long class explaination with @link if needed
 * </p>
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @author Jimmy (vSKAH) - 02/09/2026
 * @version 1.0
 * @since 2.0.0-alpha.2
 */
public class GrpcClientConfig {

    private String target;
    private String host = "127.0.0.1";
    private int port = 9000;
    private boolean useTLS;

    private Duration keepAliveTime = Duration.ofSeconds(30);
    private Duration keepAliveTimeout = Duration.ofSeconds(5);
    private boolean keepAliveWithoutCalls = true;

    private int maxInboundMessageSize = 32 * 1024 * 1024;
    private String loadBalancingPolicy = "round_robin";

    private boolean useVirtualThreads = true;

    public GrpcClientConfig setAddress(String host, int port) {
        this.host = host;
        this.port = port;
        this.target = null;
        return this;
    }

    public GrpcClientConfig setTarget(String target) {
        this.target = target;
        this.host = null;
        this.port = 9000;
        return this;
    }

    public GrpcClientConfig usePlaintext() {
        this.useTLS = false;
        return this;
    }

    public GrpcClientConfig useTLS(boolean useTLS) {
        this.useTLS = useTLS;
        return this;
    }

    public GrpcClientConfig setKeepAlive(Duration time, Duration timeout, boolean withoutCalls) {
        this.keepAliveTime = time;
        this.keepAliveTimeout = timeout;
        this.keepAliveWithoutCalls = withoutCalls;
        return this;
    }

    public GrpcClientConfig setMaxInboundMessageSize(int bytes) {
        this.maxInboundMessageSize = bytes;
        return this;
    }

    public GrpcClientConfig setLoadBalancingPolicy(String loadBalancingPolicy) {
        this.loadBalancingPolicy = loadBalancingPolicy;
        return this;
    }

    public void setUseVirtualThreads(boolean useVirtualThreads) {
        this.useVirtualThreads = useVirtualThreads;
    }

    public static GrpcClientConfig fromFile(@NotNull final FileConfiguration fileConfiguration) {
        final var config = new GrpcClientConfig();
        final ConfigurationSection section = fileConfiguration.getConfigurationSection("grpc");
        if (section == null) {
            return config;
        }

        final String target = section.getString("target", null);
        if (target != null && !target.isBlank()) {
            config.setTarget(target);
        } else {
            config.setAddress(
                    section.getString("host", "127.0.0.1"),
                    section.getInt("port", 9000)
            );
        }

        config.useTLS(section.getBoolean("use-tls", false));

        final long keepAliveTimeSec = section.getLong("keep-alive.time", 30L);
        final long keepAliveTimeoutSec = section.getLong("keep-alive.timeout", 5L);
        final boolean keepAliveWithoutCalls = section.getBoolean("keep-alive.without-calls", true);
        config.setKeepAlive(
                Duration.ofSeconds(keepAliveTimeSec),
                Duration.ofSeconds(keepAliveTimeoutSec),
                keepAliveWithoutCalls
        );

        final int maxInboundMb = section.getInt("max-inbound-message-size-mb", 32);
        config.setMaxInboundMessageSize(maxInboundMb * 1024 * 1024);

        final String lbPolicy = section.getString("load-balancing-policy", "round_robin");
        config.setLoadBalancingPolicy(lbPolicy);

        final boolean useVirtualThreads = section.getBoolean("use-virtual-threads", true);
        config.setUseVirtualThreads(useVirtualThreads);

        return config;
    }

    public String getTarget() {
        return target;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public boolean isUseTLS() {
        return useTLS;
    }

    public Duration getKeepAliveTime() {
        return keepAliveTime;
    }

    public Duration getKeepAliveTimeout() {
        return keepAliveTimeout;
    }

    public boolean isKeepAliveWithoutCalls() {
        return keepAliveWithoutCalls;
    }

    public int getMaxInboundMessageSize() {
        return maxInboundMessageSize;
    }

    public String getLoadBalancingPolicy() {
        return loadBalancingPolicy;
    }

    public boolean isUseVirtualThreads() {
        return useVirtualThreads;
    }
}
