package tech.skworks.tachyon.service.audit;

import com.google.protobuf.Empty;
import io.grpc.Status;
import io.quarkus.grpc.GrpcService;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.stream.ReactiveStreamCommands;
import io.quarkus.redis.datasource.stream.XAddArgs;
import io.smallrye.common.annotation.NonBlocking;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import tech.skworks.tachyon.service.contracts.audit.LogBatchRequest;
import tech.skworks.tachyon.service.contracts.audit.LogRequest;
import tech.skworks.tachyon.service.contracts.audit.MutinyAuditServiceGrpc;

import java.util.Map;

/**
 * Project Tachyon
 * Class AuditGrpcService
 *
 * @author  Jimmy (vSKAH) - 06/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
@GrpcService
@NonBlocking
public class AuditGrpcService extends MutinyAuditServiceGrpc.AuditServiceImplBase {

    @Inject
    Logger log;

    @Inject
    AuditConfig auditConfig;

    private final ReactiveStreamCommands<String, String, byte[]> redisStream;
    private static final XAddArgs STREAM_ARGS = new XAddArgs().maxlen(10000L).nearlyExactTrimming();

    public AuditGrpcService(ReactiveRedisDataSource redisDS) {
        this.redisStream = redisDS.stream(String.class, String.class, byte[].class);
    }

    @Override
    public Uni<Empty> logEventBatch(LogBatchRequest req) {
        if (req.getLogsCount() == 0) {
            return Uni.createFrom().item(Empty.getDefaultInstance());
        }

        return redisStream.xadd(auditConfig.streamKey(), STREAM_ARGS, Map.of("payload", req.toByteArray()))
                .invoke(_ -> log.debugf("[AuditGrpcService] %d Audit log enqueued", req.getLogsCount()))
                .replaceWith(Empty.getDefaultInstance())
                .onFailure().invoke((e) -> log.errorf(e, "[AuditGrpcService] Failed to enqueue %d audit logs", req.getLogsCount()))
                .onFailure().transform(e -> Status.UNAVAILABLE.withCause(e).withDescription("Unable to enqueue log(s) to Redis").asRuntimeException());
    }

    @Override
    public Uni<Empty> logEvent(LogRequest req) {
        return logEventBatch(LogBatchRequest.newBuilder().addLogs(req).build());
    }
}
