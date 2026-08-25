package tech.skworks.tachyon.service.audit;

import io.grpc.BindableService;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.stub.ServerCalls;
import io.quarkus.grpc.GrpcService;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.stream.ReactiveStreamCommands;
import io.quarkus.redis.datasource.stream.XAddArgs;
import io.smallrye.common.annotation.NonBlocking;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.bson.RawBsonDocument;
import org.jboss.logging.Logger;
import tech.skworks.tachyon.common.contract.AuditContract;
import tech.skworks.tachyon.common.marshaller.BsonMarshaller;

import java.nio.ByteBuffer;
import java.util.Map;

/**
 * Pure BSON gRPC Service for Audit ingestion.
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @author Jimmy (vSKAH) - 25/08/2026
 * @version 2.0
 * @since 1.0.0-SNAPSHOT
 */
@GrpcService
@NonBlocking
public class AuditGrpcService implements BindableService {

    @Inject
    Logger log;

    @Inject
    AuditConfig auditConfig;

    private final ReactiveStreamCommands<String, String, byte[]> redisStream;
    private static final XAddArgs STREAM_ARGS = new XAddArgs().maxlen(50000L).nearlyExactTrimming();

    public AuditGrpcService(ReactiveRedisDataSource redisDS) {
        this.redisStream = redisDS.stream(String.class, String.class, byte[].class);
    }

    @Override
    public ServerServiceDefinition bindService() {
        return ServerServiceDefinition.builder(AuditContract.SERVICE_NAME)
                .addMethod(AuditContract.LOG_ENTRY_BATCH_METHOD, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
                    logEventBatch(request).subscribe().with(
                            response -> {
                                responseObserver.onNext(response);
                                responseObserver.onCompleted();
                            },
                            responseObserver::onError
                    );
                }))
                .addMethod(AuditContract.DIRECT_LOG_ENTRY_METHOD, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
                    logDirect(request).subscribe().with(
                            response -> {
                                responseObserver.onNext(response);
                                responseObserver.onCompleted();
                            },
                            responseObserver::onError
                    );
                }))
                .build();
    }

    public Uni<RawBsonDocument> logEventBatch(RawBsonDocument req) {
        final byte[] payloadBytes = toByteArray(req);

        return redisStream.xadd(auditConfig.streamKey(), STREAM_ARGS, Map.of("payload", payloadBytes))
                .invoke(id -> log.debugf("[AuditGrpcService] Audit batch enqueued to stream (id: %s, %d bytes)", id, payloadBytes.length))
                .replaceWith(BsonMarshaller.EMPTY)
                .onFailure().invoke(e -> log.errorf(e, "[AuditGrpcService] Failed to enqueue audit batch to Redis"))
                .onFailure().transform(e -> Status.UNAVAILABLE.withCause(e).withDescription("Unable to enqueue audit batch to Redis").asRuntimeException());
    }

    public Uni<RawBsonDocument> logDirect(RawBsonDocument req) {
        final byte[] payloadBytes = toByteArray(req);

        return redisStream.xadd(auditConfig.streamKey(), STREAM_ARGS, Map.of("direct_payload", payloadBytes))
                .invoke(id -> log.debugf("[AuditGrpcService] Direct audit log enqueued to stream (id: %s, %d bytes)", id, payloadBytes.length))
                .replaceWith(BsonMarshaller.EMPTY)
                .onFailure().invoke(e -> log.errorf(e, "[AuditGrpcService] Failed to enqueue direct audit log to Redis"))
                .onFailure().transform(e -> Status.UNAVAILABLE.withCause(e).withDescription("Unable to enqueue direct audit log to Redis").asRuntimeException());
    }

    public static byte[] toByteArray(RawBsonDocument doc) {
        ByteBuffer nio = doc.getByteBuffer().asNIO().duplicate();
        byte[] bytes = new byte[nio.remaining()];
        nio.get(bytes);
        return bytes;
    }


}
