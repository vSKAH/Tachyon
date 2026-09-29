package tech.skworks.tachyon.core.grpc;

import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Project Tachyon
 * Class GrpcClient
 *
 * @author  Jimmy (vSKAH) - 06/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class GrpcClient implements Closeable {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tachyon/GrpcClient");

    private final ExecutorService grpcVirtualExecutor;
    private final ManagedChannel channel;

    private GrpcClient(@NotNull GrpcClientConfig config) {
        if (config.isUseVirtualThreads()) {
            this.grpcVirtualExecutor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("tachyon-grpc-", 1).factory());
        } else {
            this.grpcVirtualExecutor = Executors.newCachedThreadPool();
        }

        ManagedChannelBuilder<?> builder;
        if (config.getTarget() != null && !config.getTarget().isBlank()) {
            LOGGER.info("Creating gRPC channel towards target: {}", config.getTarget());
            builder = ManagedChannelBuilder.forTarget(config.getTarget());
        } else {
            LOGGER.info("Creating gRPC channel towards {}:{}", config.getHost(), config.getPort());
            builder = ManagedChannelBuilder.forAddress(config.getHost(), config.getPort());
        }

        if (config.isUseTLS()) {
            builder.useTransportSecurity();
        } else {
            builder.usePlaintext();
        }

        builder.executor(this.grpcVirtualExecutor)
                .keepAliveTime(config.getKeepAliveTime().toSeconds(), TimeUnit.SECONDS)
                .keepAliveTimeout(config.getKeepAliveTimeout().toSeconds(), TimeUnit.SECONDS)
                .keepAliveWithoutCalls(config.isKeepAliveWithoutCalls())
                .maxInboundMessageSize(config.getMaxInboundMessageSize())
                .defaultLoadBalancingPolicy(config.getLoadBalancingPolicy());

        builder.intercept(new  )

            builder.disableRetry();

        this.channel = builder.build();
    }

    public static GrpcClient create(@NotNull GrpcClientConfig config) {
        return new GrpcClient(config);
    }

    public ManagedChannel getChannel() {
        return channel;
    }

    public boolean isConnected() {
        ConnectivityState state = channel.getState(false);
        return state == ConnectivityState.READY;
    }

    public ConnectivityState getState(boolean requestConnection) {
        return channel.getState(requestConnection);
    }

    @Override
    public void close() {
        LOGGER.info("Shutting down gRPC Client...");
        channel.shutdown();

        try {
            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warn("Channel shutdown timed out after 5s - Forcing shutdownNow()...");
                channel.shutdownNow();
            }
        } catch (InterruptedException e) {
            channel.shutdownNow();
            Thread.currentThread().interrupt();
            LOGGER.error("Channel shutdown was interrupted, some data can be lost!", e);
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
            LOGGER.error("GrpcVirtualExecutor shutdown was interrupted, some data can be lost!", e);
        }
        LOGGER.info("gRPC Client shutdown complete.");
    }

}
