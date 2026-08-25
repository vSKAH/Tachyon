package tech.skworks.tachyon.plugin.core.grpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import tech.skworks.tachyon.plugin.spigot.TachyonCore;
import tech.skworks.tachyon.plugin.common.util.TachyonLogger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Project Tachyon
 * Class GrpcClientManager
 *
 * @author  Jimmy (vSKAH) - 06/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class BackendStubProvider {

    private static final TachyonLogger LOGGER = TachyonCore.getModuleLogger("BackendStubProvider");

    private final ExecutorService grpcVirtualExecutor;

    private final ManagedChannel channel;

    public BackendStubProvider(String host, int port) {
        this.grpcVirtualExecutor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("tachyon-grpc-", 1).factory());
        LOGGER.info("Initializing gRPC client towards {}:{} (Virtual Threads)", host, port);
        this.channel = ManagedChannelBuilder.forAddress(host, port).useTransportSecurity().executor(grpcVirtualExecutor)
                .keepAliveTime(30, TimeUnit.SECONDS).keepAliveTimeout(5, TimeUnit.SECONDS).keepAliveWithoutCalls(true)
                .maxInboundMessageSize(32 * 1024 * 1024).enableRetry().maxRetryAttempts(3).defaultLoadBalancingPolicy("round_robin").build();
    }

    public ManagedChannel getChannel() {
        return channel;
    }

    public void shutdown() {
        LOGGER.info("Shutdown of gRPC Client Manager has been started...");
        channel.shutdown();

        try {
            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warn("Channel shutdown timed out after 5s - Forcing shutdownNow()...");
                channel.shutdownNow();
            }
        } catch (InterruptedException e) {
            channel.shutdownNow();
            Thread.currentThread().interrupt();
            LOGGER.error(e, "Channel shutdown was interrupted, some data can be lost!");
        }

        grpcVirtualExecutor.shutdown();
        try {
            if (!grpcVirtualExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warn("GrpcVirtualExecutor shutdown timed out after 5s. -Forcing shutdownNow()...");
                grpcVirtualExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            grpcVirtualExecutor.shutdownNow();
            Thread.currentThread().interrupt();
            LOGGER.error(e, "GrpcVirtualExecutor shutdown was interrupted, some data can be lost!");
        }
        LOGGER.info("GrpcClientManager shutdown complete.");
    }
}
