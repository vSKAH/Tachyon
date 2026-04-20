package tech.skworks.tachyon.service.infra;

import io.quarkus.grpc.GrpcService;
import io.smallrye.common.annotation.NonBlocking;
import io.smallrye.mutiny.Uni;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import tech.skworks.tachyon.service.contracts.system.MutinySystemGrpc;
import tech.skworks.tachyon.service.contracts.system.PingRequest;
import tech.skworks.tachyon.service.contracts.system.PingResponse;

/**
 * Project Tachyon
 * Class SystemGrpcService
 *
 * @author  Jimmy (vSKAH) - 08/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
@GrpcService
@NonBlocking
public class SystemGrpcService extends MutinySystemGrpc.SystemImplBase {

    @ConfigProperty(name = "quarkus.application.name")
    String applicationName;

    @Override
    public Uni<PingResponse> ping(PingRequest request) {
        long serverTime = System.currentTimeMillis();
        return Uni.createFrom().item(PingResponse.newBuilder().setServerTime(serverTime).setTachyonServerName(applicationName).build());
    }

}
