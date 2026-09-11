package tech.skworks.tachyon.plugin.core.grpc;

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
    private boolean retryEnabled = true;
    private int maxRetryAttempts = 3;
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

    public GrpcClientConfig setRetry(boolean enabled, int maxAttempts) {
        this.retryEnabled = enabled;
        this.maxRetryAttempts = maxAttempts;
        return this;
    }

    public GrpcClientConfig setUseVirtualThreads(boolean useVirtualThreads) {
        this.useVirtualThreads = useVirtualThreads;
        return this;
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

    public boolean isRetryEnabled() {
        return retryEnabled;
    }

    public int getMaxRetryAttempts() {
        return maxRetryAttempts;
    }

    public String getLoadBalancingPolicy() {
        return loadBalancingPolicy;
    }

    public boolean isUseVirtualThreads() {
        return useVirtualThreads;
    }
}
