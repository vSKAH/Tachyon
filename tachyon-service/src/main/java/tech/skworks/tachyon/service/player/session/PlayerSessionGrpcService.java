package tech.skworks.tachyon.service.player.session;

import io.grpc.BindableService;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.stub.ServerCalls;
import io.quarkus.grpc.GrpcService;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.smallrye.common.annotation.NonBlocking;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.bson.RawBsonDocument;
import org.jboss.logging.Logger;
import tech.skworks.tachyon.common.contract.SessionContract;
import tech.skworks.tachyon.common.marshaller.BsonMarshaller;
import tech.skworks.tachyon.service.infra.RedisKeys;

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
public class PlayerSessionGrpcService implements BindableService {

    @Inject
    Logger log;

    private final ReactiveKeyCommands<String> redisKey;

    public PlayerSessionGrpcService(ReactiveRedisDataSource redisDS) {
        this.redisKey = redisDS.key();
    }

    public Uni<RawBsonDocument> freePlayer(RawBsonDocument request) {
        if (!request.containsKey("uuid") || !request.isString("uuid")) {
            return Uni.createFrom().failure(Status.INVALID_ARGUMENT.withDescription("'uuid' must be present to release player profile").asRuntimeException());
        }

        final var uuid = request.getString("uuid").getValue();
        log.infof("[PlayerDataGrpcService] freePlayer() called for %s — releasing state.", uuid);

        return redisKey.del(RedisKeys.state(uuid))
                .invoke(deleted -> {
                    if (deleted != null && deleted > 0) log.infof("[PlayerDataGrpcService] State key deleted for %s.", uuid);
                    else log.debugf("[PlayerDataGrpcService] No state key found to delete for %s (already free).", uuid);
        }).replaceWith(BsonMarshaller.EMPTY)
                .onFailure().invoke(e -> log.errorf(e, "[PlayerDataGrpcService] freePlayer() failed for %s.", uuid))
                .onFailure().transform(e -> Status.UNAVAILABLE.withCause(e).withDescription("Failed to delete state key in Redis!").asRuntimeException());
    }


    public Uni<RawBsonDocument> playerHeartBeatBatch(RawBsonDocument request) {
        if (!request.containsKey("uuids") || !request.isArray("uuids")) {
            return Uni.createFrom().item(BsonMarshaller.EMPTY);
        }
        var uuids = request.getArray("uuids");
        if (uuids.isEmpty()) return Uni.createFrom().item(BsonMarshaller.EMPTY);

        log.debugf("[PlayerDataGrpcService] Heartbeat batch received — renewing TTL for %d player(s).", uuids.size());

        return Multi.createFrom().iterable(uuids)
                .onItem().transformToUniAndMerge(value -> {
                    final var playerId = value.asString().getValue();
                    return redisKey.expire(RedisKeys.state(playerId), RedisKeys.STATE_TTL_SECONDS)
                            .invoke(exists -> {
                                if (Boolean.FALSE.equals(exists)) {
                                    log.debugf("[PlayerDataGrpcService] Heartbeat: state key missing for %s (may be already FREE).", playerId);
                                }
                            })
                            .onFailure().recoverWithItem(false);
                }).collect().asList()
                .replaceWith(BsonMarshaller.EMPTY)
                .onFailure().invoke(e -> log.errorf(e, "[PlayerDataGrpcService] Heartbeat batch completely failed."))
                .onFailure().transform(e -> Status.UNAVAILABLE.withCause(e).withDescription("Heartbeat batch failed: " + e.getMessage()).asRuntimeException());

    }

    @Override
    public ServerServiceDefinition bindService() {
        return ServerServiceDefinition.builder(SessionContract.SERVICE_NAME)
                .addMethod(SessionContract.FREE_PLAYER_METHOD, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
                    freePlayer(request).subscribe().with(response -> {
                        responseObserver.onNext(response);
                        responseObserver.onCompleted();
                    }, responseObserver::onError);
                }))

                .addMethod(SessionContract.HEARTBEAT_BATCH_METHOD, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
                    playerHeartBeatBatch(request).subscribe().with(response -> {
                        responseObserver.onNext(response);
                        responseObserver.onCompleted();
                    }, responseObserver::onError);
                }))


                .build();
    }
}
