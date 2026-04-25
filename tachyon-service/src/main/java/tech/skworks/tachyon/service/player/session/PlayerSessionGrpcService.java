package tech.skworks.tachyon.service.player.session;

import com.google.protobuf.Empty;
import io.grpc.Status;
import io.quarkus.grpc.GrpcService;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.smallrye.common.annotation.NonBlocking;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import tech.skworks.tachyon.service.contracts.player.session.FreePlayerRequest;
import tech.skworks.tachyon.service.contracts.player.session.MutinyPlayerSessionServiceGrpc;
import tech.skworks.tachyon.service.contracts.player.session.PlayerHeartBeatBatchRequest;
import tech.skworks.tachyon.service.contracts.player.session.PlayerHeartBeatRequest;

/**
 * Project Tachyon
 * Class PlayerSessionGrpcService
 *
 * @author  Jimmy (vSKAH) - 21/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */

@GrpcService
@NonBlocking
public class PlayerSessionGrpcService extends MutinyPlayerSessionServiceGrpc.PlayerSessionServiceImplBase {

    @Inject
    Logger log;

    private final ReactiveKeyCommands<String> redisKey;

    public PlayerSessionGrpcService(ReactiveRedisDataSource redisDS) {
        this.redisKey = redisDS.key();
    }

    @Override
    public Uni<Empty> freePlayer(FreePlayerRequest req) {
        final String uuid = req.getUuid();
        log.infof("[PlayerDataGrpcService] freePlayer() called for %s — releasing state.", uuid);

        return redisKey.del("player:state:" + uuid)
                .invoke(deleted -> {
                    if (deleted != null && deleted > 0)
                        log.infof("[PlayerDataGrpcService] State key deleted for %s.", uuid);
                    else
                        log.debugf("[PlayerDataGrpcService] No state key found to delete for %s (already free).", uuid);
                })
                .replaceWith(Empty.getDefaultInstance())
                .onFailure().invoke(e -> log.errorf(e, "[PlayerDataGrpcService] freePlayer() failed for %s.", uuid))
                .onFailure().transform(e -> Status.UNAVAILABLE.withCause(e).withDescription("Failed to delete state key in Redis!").asRuntimeException());
    }


    @Override
    public Uni<Empty> playerHeartBeatBatch(PlayerHeartBeatBatchRequest req) {
        if (req.getUuidsCount() <= 0) return Uni.createFrom().item(Empty.getDefaultInstance());

        log.debugf("[PlayerDataGrpcService] Heartbeat batch received — renewing TTL for %d player(s).", req.getUuidsCount());

        return Multi.createFrom().iterable(req.getUuidsList())
                .onItem().transform(playerId ->
                        redisKey.expire("player:state:" + playerId, 30)
                                .invoke(exists -> {
                                    if (Boolean.FALSE.equals(exists)) {
                                        log.debugf("[PlayerDataGrpcService] Heartbeat: state key missing for %s (may be already FREE).", playerId);
                                    }
                                })
                                .onFailure().recoverWithItem(false)
                )
                .collect().asList()
                .replaceWith(Empty.getDefaultInstance())
                .onFailure().invoke(e -> log.errorf(e, "[PlayerDataGrpcService] Heartbeat batch completely failed."))
                .onFailure().transform(e -> Status.UNAVAILABLE.withCause(e).withDescription("Heartbeat batch failed: " + e.getMessage()).asRuntimeException());
    }

    @Override
    public Uni<Empty> playerHeartBeat(PlayerHeartBeatRequest req) {
        return playerHeartBeatBatch(PlayerHeartBeatBatchRequest.newBuilder().addUuids(req.getUuid()).build());
    }
}
