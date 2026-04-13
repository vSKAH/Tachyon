package tech.skworks.tachyon.infra;

import io.grpc.stub.StreamObserver;
import io.quarkus.grpc.GrpcService;
import io.smallrye.common.annotation.Blocking;
import tech.skworks.tachyon.contracts.system.PingRequest;
import tech.skworks.tachyon.contracts.system.PingResponse;
import tech.skworks.tachyon.contracts.system.SystemGrpc;

/**
 * Project Tachyon
 * Class SystemGrpcService
 *
 * @author  Jimmy (vSKAH) - 08/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
@GrpcService
@Blocking
public class SystemGrpcService extends SystemGrpc.SystemImplBase {

    @Override
    public void ping(PingRequest request, StreamObserver<PingResponse> obs) {
        obs.onNext(PingResponse.newBuilder().setServerTime(System.currentTimeMillis()).setMessage("PONG - Quarkus is online!").build());
        obs.onCompleted();
    }

}
