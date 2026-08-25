package tech.skworks.tachyon.service.infra;

import io.grpc.BindableService;
import io.grpc.ServerServiceDefinition;
import io.grpc.stub.ServerCalls;
import io.quarkus.grpc.GrpcService;
import io.smallrye.common.annotation.NonBlocking;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import tech.skworks.tachyon.common.contract.SystemContract;
import tech.skworks.tachyon.common.marshaller.BsonMarshaller;

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
public class SystemGrpcService implements BindableService {

    @ConfigProperty(name = "quarkus.application.name")
    String applicationName;


    @Override
    public ServerServiceDefinition bindService() {
        return ServerServiceDefinition.builder(SystemContract.SERVICE_NAME)
                .addMethod(SystemContract.PING_METHOD, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
                    var response = new BsonDocument()
                            .append("server_time", new BsonInt64(System.currentTimeMillis()))
                            .append("tachyon_server_name", new BsonString(applicationName));

                    responseObserver.onNext(BsonMarshaller.toRawBsonDocument(response));
                    responseObserver.onCompleted();
                }))
                .build();
    }
}
