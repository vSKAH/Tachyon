package tech.skworks.tachyon.infra;

import io.quarkus.grpc.GrpcService;
import io.smallrye.common.annotation.NonBlocking;
import io.smallrye.mutiny.Uni;
import tech.skworks.tachyon.contracts.system.MutinySystemGrpc;
import tech.skworks.tachyon.contracts.system.PingRequest;
import tech.skworks.tachyon.contracts.system.PingResponse;

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

    @Override
    public Uni<PingResponse> ping(PingRequest request) {
        long serverTime = System.currentTimeMillis();
        return Uni.createFrom().item(PingResponse.newBuilder().setServerTime(serverTime).build());
    }

}
