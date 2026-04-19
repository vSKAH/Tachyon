package tech.skworks.tachyon.plugin.internal;

import tech.skworks.tachyon.service.contracts.audit.AuditServiceGrpc;
import tech.skworks.tachyon.service.contracts.player.PlayerDataServiceGrpc;
import tech.skworks.tachyon.service.contracts.snapshot.SnapshotServiceGrpc;
import tech.skworks.tachyon.service.contracts.system.PingRequest;
import tech.skworks.tachyon.service.contracts.system.PingResponse;
import tech.skworks.tachyon.service.contracts.system.SystemGrpc;
import tech.skworks.tachyon.libs.io.grpc.ManagedChannel;
import tech.skworks.tachyon.libs.io.grpc.ManagedChannelBuilder;
import tech.skworks.tachyon.plugin.spigot.TachyonCore;
import tech.skworks.tachyon.plugin.internal.util.TachyonLogger;

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
public class GrpcClientManager {

    private final ManagedChannel channel;

    private final PlayerDataServiceGrpc.PlayerDataServiceBlockingStub playerStub;
    private final AuditServiceGrpc.AuditServiceBlockingStub auditStub;
    private final SnapshotServiceGrpc.SnapshotServiceBlockingStub snapshotStub;
    private final SystemGrpc.SystemBlockingStub systemStub;

    private final ExecutorService grpcVirtualExecutor;
    private static final TachyonLogger LOGGER = TachyonCore.getModuleLogger("GrpcClientManager");

    public GrpcClientManager(String host, int port) {
        this.grpcVirtualExecutor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("tachyon-grpc-", 1).factory());
        LOGGER.info("Initializing gRPC client towards {}:{} (Virtual Threads)", host, port);
        this.channel = ManagedChannelBuilder.forAddress(host, port).useTransportSecurity().executor(grpcVirtualExecutor).keepAliveTime(30, TimeUnit.SECONDS).keepAliveTimeout(5, TimeUnit.SECONDS).keepAliveWithoutCalls(true).maxInboundMessageSize(32 * 1024 * 1024).enableRetry().maxRetryAttempts(3).defaultLoadBalancingPolicy("round_robin").build();
        this.playerStub = PlayerDataServiceGrpc.newBlockingStub(channel);
        this.auditStub = AuditServiceGrpc.newBlockingStub(channel);
        this.snapshotStub = SnapshotServiceGrpc.newBlockingStub(channel);
        this.systemStub = SystemGrpc.newBlockingStub(channel);
    }


    public boolean pingBackend() {
        try {
            long send = System.currentTimeMillis();
            PingResponse response = systemStub.withDeadlineAfter(3, TimeUnit.SECONDS).ping(PingRequest.newBuilder().setClientTime(send).build());
            boolean success = response != null;
            if (success) {
                long time = response.getServerTime() - send;
                LOGGER.info("The ping took {}ms to respond", time);
            }
            return success;
        } catch (Exception e) {
            LOGGER.error(e, "Unable to ping Tachyon Service");
            return false;
        }
    }

    public void shutdown() {
        LOGGER.info("The shutdown has been started...");
        channel.shutdown();

        try {
            if (!channel.awaitTermination(10, TimeUnit.SECONDS)) {
                LOGGER.error("Channel shutdown timed out after 10s. Forcing shutdownNow()...");
                channel.shutdownNow();
            }
        } catch (InterruptedException e) {
            channel.shutdownNow();
            Thread.currentThread().interrupt();
            LOGGER.error(e, "Channel shutdown was interrupted, some data can be lost!");
        }

        grpcVirtualExecutor.shutdown();
        try {
            if (!grpcVirtualExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                LOGGER.error("GrpcVirtualExecutor shutdown timed out after 10s. Forcing shutdownNow()...");
                grpcVirtualExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            grpcVirtualExecutor.shutdownNow();
            Thread.currentThread().interrupt();
            LOGGER.error(e, "GrpcVirtualExecutor shutdown was interrupted, some data can be lost!");
        }
        LOGGER.info("GrpcClientManager shutdown complete.");
    }

    public PlayerDataServiceGrpc.PlayerDataServiceBlockingStub getPlayerStub(int deadline) {
        return playerStub.withDeadlineAfter(deadline, TimeUnit.SECONDS);
    }

    public AuditServiceGrpc.AuditServiceBlockingStub getAuditStub(int deadline) {
        return auditStub.withDeadlineAfter(deadline, TimeUnit.SECONDS);
    }

    public SnapshotServiceGrpc.SnapshotServiceBlockingStub getSnapshotStub(int deadline) {
        return snapshotStub.withDeadlineAfter(deadline, TimeUnit.SECONDS);
    }

    public SystemGrpc.SystemBlockingStub getSystemStub() {
        return systemStub;
    }

    public ExecutorService getGrpcVirtualExecutor() {
        return grpcVirtualExecutor;
    }
}
