package tech.skworks.tachyon.audit;

import io.grpc.Status;
import io.quarkus.grpc.GrpcService;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.stream.ReactiveStreamCommands;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import tech.skworks.tachyon.contracts.audit.LogBatchRequest;
import tech.skworks.tachyon.contracts.audit.LogRequest;
import tech.skworks.tachyon.contracts.audit.MutinyAuditServiceGrpc;
import tech.skworks.tachyon.contracts.common.StandardResponse;

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
public class AuditGrpcService extends MutinyAuditServiceGrpc.AuditServiceImplBase {

    @Inject
    Logger log;

    @Inject
    AuditConfig config;

    private final ReactiveStreamCommands<String, String, byte[]> redisStream;
    private static final StandardResponse SUCCESS = StandardResponse.newBuilder().setSuccess(true).build();

    public AuditGrpcService(ReactiveRedisDataSource redisDS) {
        this.redisStream = redisDS.stream(String.class, String.class, byte[].class);
    }

    @Override
    public Uni<StandardResponse> logEventBatch(LogBatchRequest req) {
        if (req.getLogsCount() == 0) {
            return Uni.createFrom().item(SUCCESS);
        }

        return redisStream.xadd(config.streamKey(), Map.of("payload", req.toByteArray()))
                .replaceWith(SUCCESS)
                .onFailure().invoke(e -> log.error("Failed to enqueue audit batch", e))
                .onFailure().transform(e -> Status.INTERNAL.withDescription("AUDIT_ENQUEUE_ERROR").withCause(e).asRuntimeException());
    }

    @Override
    public Uni<StandardResponse> logEvent(LogRequest req) {
        return logEventBatch(LogBatchRequest.newBuilder().addLogs(req).build());
    }
}
