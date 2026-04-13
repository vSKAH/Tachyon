package tech.skworks.tachyon.player;

import com.google.protobuf.Any;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.util.JsonFormat;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import io.grpc.Status;
import io.quarkus.grpc.GrpcService;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.quarkus.redis.datasource.stream.ReactiveStreamCommands;
import io.quarkus.redis.datasource.stream.XAddArgs;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.quarkus.redis.datasource.value.SetArgs;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import tech.skworks.tachyon.contracts.common.StandardResponse;
import tech.skworks.tachyon.contracts.player.*;
import tech.skworks.tachyon.infra.DynamicProtobufRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Project Tachyon
 * Class PlayerDataGrpcService
 *
 * @author  Jimmy (vSKAH) - 06/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
@GrpcService
public class PlayerDataGrpcService extends MutinyPlayerDataServiceGrpc.PlayerDataServiceImplBase {

    @Inject
    Logger log;
    @Inject
    PlayerConfig config;
    @Inject
    MongoClient mongo;
    @Inject
    DynamicProtobufRegistry protobufRegistry;

    @ConfigProperty(name = "tachyon.database.name")
    String dbName;

    private final ReactiveValueCommands<String, String> redisString;
    private final ReactiveValueCommands<String, byte[]> redisBytes;
    private final ReactiveStreamCommands<String, String, byte[]> redisStream;
    private final ReactiveKeyCommands<String> redisKey;

    private static final XAddArgs STREAM_ARGS = new XAddArgs().maxlen(10000L).nearlyExactTrimming();

    private static final StandardResponse SUCCESS = StandardResponse.newBuilder().setSuccess(true).build();

    private static StandardResponse failure(String message) {
        return StandardResponse.newBuilder().setSuccess(false).setMessage(message).build();
    }

    private static PlayerResponse playerError(String uuid, String errorCode, String details) {
        return PlayerResponse.newBuilder().setUuid(uuid).setErrorCode(errorCode).setErrorDetails(details).build();
    }

    public PlayerDataGrpcService(ReactiveRedisDataSource redisDS) {
        this.redisString = redisDS.value(String.class);
        this.redisBytes = redisDS.value(byte[].class);
        this.redisStream = redisDS.stream(String.class, String.class, byte[].class);
        this.redisKey = redisDS.key();
    }

    @Override
    public Uni<PlayerResponse> getPlayer(PlayerRequest req) {
        final String uuid = req.getUuid();
        final String dirtyKey = "player:dirty:" + uuid;
        final String stateKey = "player:state:" + uuid;
        final String cacheKey = "player:cache:" + uuid;

        log.debugf("[PlayerDataGrpcService] getPlayer() called for %s.", uuid);

        return redisString.get(dirtyKey).flatMap(dirty -> {
            if (dirty != null) {
                log.infof("[PlayerDataGrpcService] getPlayer() for %s blocked — DATA_DIRTY (save in progress).", uuid);
                return Uni.createFrom().item(playerError(uuid, "DATA_DIRTY", "Player data is currently being saved"));
            }

            return redisString.setAndChanged(stateKey, "USED", new SetArgs().nx().ex(30)).flatMap(acquired -> {
                if (!acquired) {
                    log.infof("[PlayerDataGrpcService] getPlayer() for %s blocked — ALREADY_LOADED (active on another server).", uuid);
                    return Uni.createFrom().item(playerError(uuid, "ALREADY_LOADED", "Player is active on another server"));
                }

                log.debugf("[PlayerDataGrpcService] State set to USED for %s — checking cache...", uuid);

                return redisBytes.get(cacheKey).flatMap(cached -> {
                    if (cached != null) {
                        log.infof("[PlayerDataGrpcService] Cache HIT for %s (%d bytes).", uuid, cached.length);
                        return parseResponse(cached);
                    }

                    log.infof("[PlayerDataGrpcService] Cache MISS for %s — reading from MongoDB.", uuid);
                    return readFromMongo(uuid).flatMap(response -> {
                        if (!response.getErrorCode().isEmpty()) {
                            log.errorf("[PlayerDataGrpcService] MongoDB read failed for %s (code: %s) — releasing state.", uuid, response.getErrorCode());
                            return redisKey.del(stateKey).replaceWith(response);
                        }
                        log.infof("[PlayerDataGrpcService] MongoDB read successful for %s — %d component(s) loaded, caching.", uuid, response.getComponentsCount());
                        return redisBytes.setex(cacheKey, 60, response.toByteArray()).replaceWith(response);
                    });
                });
            });
        }).onFailure().call(e -> {
            log.errorf(e, "[PlayerDataGrpcService] Unexpected failure in getPlayer() for %s — clearing state.", uuid);
            return redisKey.del(stateKey);
        }).onFailure().recoverWithItem(e -> playerError(uuid, "INTERNAL_ERROR", e.getMessage()));
    }


    @Override
    public Uni<StandardResponse> saveProfile(SaveProfileRequest req) {
        final String uuid = req.getUuid();
        final String dirtyKey = "player:dirty:" + uuid;

        log.debugf("[PlayerDataGrpcService] saveProfile() called for %s (%d component(s)).", uuid, req.getComponentsCount());

        return redisString.setex(dirtyKey, 20, "1").chain(() -> redisStream.xadd(config.streamKey(), STREAM_ARGS, Map.of("payload_profile", req.toByteArray()))).invoke(id -> log.infof("[PlayerDataGrpcService] saveProfile() enqueued for %s (stream message id: %s).", uuid, id)).replaceWith(SUCCESS).onFailure().call(e -> {
            log.errorf(e, "[PlayerDataGrpcService] saveProfile() failed to enqueue for %s — releasing dirty key.", uuid);
            return redisKey.del(dirtyKey);
        }).onFailure().recoverWithItem(e -> failure("saveProfile failed: " + e.getMessage()));
    }

    @Override
    public Uni<StandardResponse> saveComponent(SaveComponentRequest req) {
        final String uuid = req.getUuid();
        final String dirtyKey = "player:dirty:" + uuid;
        final String typeUrl = req.getComponent().getTypeUrl();

        log.debugf("[PlayerDataGrpcService] saveComponent() called for %s (type: %s).", uuid, typeUrl);

        return redisString.setex(dirtyKey, 20, "1").chain(() -> redisStream.xadd(config.streamKey(), STREAM_ARGS, Map.of("payload", req.toByteArray()))).invoke(id -> log.infof("[PlayerDataGrpcService] saveComponent() enqueued for %s — type: %s, stream id: %s.", uuid, typeUrl, id)).replaceWith(SUCCESS).onFailure().call(e -> {
            log.errorf(e, "[PlayerDataGrpcService] saveComponent() failed to enqueue for %s — releasing dirty key.", uuid);
            return redisKey.del(dirtyKey);
        }).onFailure().recoverWithItem(e -> failure("saveComponent failed: " + e.getMessage()));
    }

    @Override
    public Uni<StandardResponse> deleteComponent(DeleteComponentRequest req) {
        final String uuid = req.getUuid();
        final String dirtyKey = "player:dirty:" + uuid;
        final String url = req.getComponentUrl();

        log.debugf("[PlayerDataGrpcService] deleteComponent() called for %s (url: %s).", uuid, url);

        return redisString.setex(dirtyKey, 20, "1").chain(() -> redisStream.xadd(config.streamKey(), STREAM_ARGS, Map.of("payload_delete", req.toByteArray()))).invoke(id -> log.infof("[PlayerDataGrpcService] deleteComponent() enqueued for %s — url: %s, stream id: %s.", uuid, url, id)).replaceWith(SUCCESS).onFailure().call(e -> {
            log.errorf(e, "[PlayerDataGrpcService] deleteComponent() failed to enqueue for %s — releasing dirty key.", uuid);
            return redisKey.del(dirtyKey);
        }).onFailure().recoverWithItem(e -> failure("deleteComponent failed: " + e.getMessage()));
    }

    @Override
    public Uni<StandardResponse> freePlayer(PlayerRequest req) {
        final String uuid = req.getUuid();
        log.infof("[PlayerDataGrpcService] freePlayer() called for %s — releasing state.", uuid);

        return redisKey.del("player:state:" + uuid).invoke(deleted -> {
            if (deleted > 0) log.infof("[PlayerDataGrpcService] State key deleted for %s.", uuid);
            else log.debugf("[PlayerDataGrpcService] No state key found to delete for %s (already free).", uuid);
        }).replaceWith(SUCCESS).onFailure().recoverWithItem(e -> {
            log.errorf(e, "[PlayerDataGrpcService] freePlayer() failed for %s.", uuid);
            return failure("freePlayer failed: " + e.getMessage());
        });
    }


    @Override
    public Uni<StandardResponse> playerHeartBeatBatch(HeartBeatBatchRequest req) {
        if (req.getBeatCount() <= 0) return Uni.createFrom().item(SUCCESS);

        log.debugf("[PlayerDataGrpcService] Heartbeat batch received — renewing TTL for %d player(s).", req.getBeatCount());

        List<Uni<Boolean>> expirations = req.getBeatList().stream().map(beat -> redisKey.expire("player:state:" + beat.getUuid(), 30).invoke(exists -> {
            if (!exists) {
                log.debugf("[PlayerDataGrpcService] Heartbeat: state key missing for %s (player may be SAVING or already FREE).", beat.getUuid());
            }
        })).toList();

        return Uni.combine().all().unis(expirations).with(_ -> SUCCESS).onFailure().recoverWithItem(e -> {
            log.errorf(e, "[PlayerDataGrpcService] Heartbeat batch failed.");
            return failure("Heartbeat batch failed: " + e.getMessage());
        });
    }

    @Override
    public Uni<StandardResponse> playerHeartBeat(PlayerRequest req) {
        return playerHeartBeatBatch(HeartBeatBatchRequest.newBuilder().addBeat(req).build());
    }


    private Uni<PlayerResponse> readFromMongo(String uuid) {
        return Uni.createFrom().item(() -> {
            Document doc = mongo.getDatabase(dbName).getCollection(config.collection()).find(Filters.eq("uuid", uuid)).first();

            PlayerResponse.Builder res = PlayerResponse.newBuilder().setUuid(uuid);

            if (doc == null) {
                log.infof("[PlayerDataGrpcService] No document found in MongoDB for %s — returning empty profile.", uuid);
                return res.build();
            }

            if (!doc.containsKey("components")) {
                log.debugf("[PlayerDataGrpcService] Document for %s has no 'components' field.", uuid);
                return res.build();
            }

            Document components = doc.get("components", Document.class);
            int loaded = 0;
            int skipped = 0;

            for (String dbKey : components.keySet()) {
                Document compDoc = components.get(dbKey, Document.class);
                String shortType = compDoc.getString("@type");

                if (shortType == null) {
                    log.warnf("[PlayerDataGrpcService] Component '%s' missing '@type' field for player %s — skipped.", dbKey, uuid);
                    skipped++;
                    continue;
                }

                Document compForJson = new Document(compDoc);
                compForJson.put("@type", "type.googleapis.com/" + shortType);

                Any any = buildAnyFromJson(shortType, compForJson.toJson());
                if (any != null) {
                    res.addComponents(any);
                    loaded++;
                } else {
                    log.warnf("[PlayerDataGrpcService] Unknown proto type '%s' for player %s — is the .desc file loaded in config/protos/?", shortType, uuid);
                    skipped++;
                }
            }

            log.infof("[PlayerDataGrpcService] MongoDB read for %s: %d component(s) loaded, %d skipped.", uuid, loaded, skipped);
            return res.build();

        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool()).onFailure().recoverWithItem(e -> {
            log.errorf(e, "[PlayerDataGrpcService] MongoDB read failed for %s.", uuid);
            return playerError(uuid, "FETCH_ERROR", "MongoDB read failed: " + e.getMessage());
        });
    }

    private Uni<PlayerResponse> parseResponse(byte[] bytes) {
        return Uni.createFrom().item(() -> {
            try {
                return PlayerResponse.parseFrom(bytes);
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse cached PlayerResponse", e);
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    private Any buildAnyFromJson(String protoFullName, String json) {
        try {
            Descriptors.Descriptor descriptor = protobufRegistry.findDescriptor(protoFullName);
            if (descriptor == null) return null;

            DynamicMessage.Builder builder = DynamicMessage.newBuilder(descriptor);
            JsonFormat.parser().usingTypeRegistry(protobufRegistry.getTypeRegistry()).ignoringUnknownFields().merge(json, builder);

            return Any.pack(builder.build());
        } catch (Exception e) {
            log.errorf(e, "buildAnyFromJson failed for type '%s'", protoFullName);
            return null;
        }
    }
}
