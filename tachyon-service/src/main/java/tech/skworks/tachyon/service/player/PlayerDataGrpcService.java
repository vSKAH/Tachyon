package tech.skworks.tachyon.service.player;

import com.google.protobuf.Any;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Empty;
import com.google.protobuf.util.JsonFormat;
import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.model.Filters;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.grpc.GrpcService;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.quarkus.mongodb.reactive.ReactiveMongoCollection;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.quarkus.redis.datasource.stream.ReactiveStreamCommands;
import io.quarkus.redis.datasource.stream.XAddArgs;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.quarkus.redis.datasource.value.SetArgs;
import io.smallrye.common.annotation.NonBlocking;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import tech.skworks.tachyon.service.contracts.player.*;
import tech.skworks.tachyon.service.contracts.player.*;
import tech.skworks.tachyon.service.infra.DynamicProtobufRegistry;

import java.time.Duration;
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
@NonBlocking
public class PlayerDataGrpcService extends MutinyPlayerDataServiceGrpc.PlayerDataServiceImplBase {

    @Inject
    Logger log;
    @Inject
    PlayerConfig config;
    @Inject
    DynamicProtobufRegistry protobufRegistry;


    @ConfigProperty(name = "quarkus.mongodb.database")
    String dbName;
    @Inject
    ReactiveMongoClient mongoClient;
    private ReactiveMongoCollection<Document> playersCollection;


    private static final XAddArgs STREAM_ARGS = new XAddArgs().maxlen(10000L).nearlyExactTrimming();
    private final ReactiveValueCommands<String, String> redisString;
    private final ReactiveValueCommands<String, byte[]> redisBytes;
    private final ReactiveStreamCommands<String, String, byte[]> redisStream;
    private final ReactiveKeyCommands<String> redisKey;


    public PlayerDataGrpcService(ReactiveRedisDataSource redisDS) {
        this.redisString = redisDS.value(String.class);
        this.redisBytes = redisDS.value(byte[].class);
        this.redisStream = redisDS.stream(String.class, String.class, byte[].class);
        this.redisKey = redisDS.key();
    }

    @PostConstruct
    void init() {
        this.playersCollection = mongoClient.getDatabase(dbName).getCollection(config.collection());
    }

    @Override
    public Uni<PlayerResponse> getPlayer(PlayerRequest req) {
        final String uuid = req.getUuid();
        final String dirtyKey = "player:dirty:" + uuid;
        final String stateKey = "player:state:" + uuid;
        final String cacheKey = "player:cache:" + uuid;

        log.debugf("[PlayerDataGrpcService] getPlayer() called for %s.", uuid);

        return redisString.get(dirtyKey).chain(dirty -> {
                    if (dirty != null) {
                        log.infof("[PlayerDataGrpcService] getPlayer() for %s blocked — DATA_DIRTY (save in progress).", uuid);
                        return Uni.createFrom().failure(Status.CANCELLED.withDescription("DATA_DIRTY: Player data is currently being saved (Player: " + uuid + ")").asRuntimeException());
                    }

                    return redisString.setAndChanged(stateKey, "USED", new SetArgs().nx().ex(30)).chain(acquired -> {
                        if (!acquired) {
                            log.infof("[PlayerDataGrpcService] getPlayer() for %s blocked — ALREADY_LOADED (active on another server).", uuid);
                            return Uni.createFrom().failure(Status.CANCELLED.withDescription("ALREADY_LOADED: Player data is currently active on another server (Player: " + uuid + ")").asRuntimeException());
                        }

                        log.debugf("[PlayerDataGrpcService] State set to USED for %s — checking cache...", uuid);

                        return redisBytes.get(cacheKey).chain(cached -> {
                            if (cached != null) {
                                log.infof("[PlayerDataGrpcService] Cache HIT for %s (%d bytes).", uuid, cached.length);
                                return parseResponse(cached);
                            }

                            log.infof("[PlayerDataGrpcService] Cache MISS for %s — reading from MongoDB.", uuid);
                            return readFromMongo(uuid)
                                    .chain(response -> {
                                        log.infof("[PlayerDataGrpcService] MongoDB read successful for %s — %d component(s) loaded, caching.", uuid, response.getComponentsCount());
                                        return redisBytes.setex(cacheKey, 60, response.toByteArray()).replaceWith(response);
                                    })
                                    .onFailure().invoke(() -> log.errorf("[PlayerDataGrpcService] MongoDB read failed for %s — releasing state.", uuid))
                                    .onFailure().call(() -> redisKey.del(stateKey));
                        });
                    });
                })
                .onFailure().invoke(e -> {
                    if (e instanceof StatusRuntimeException sre && sre.getStatus().getCode() == Status.Code.CANCELLED) {
                        log.infof("[PlayerDataGrpcService] getPlayer() gracefully aborted for %s: %s", uuid, sre.getStatus().getDescription());
                    } else {
                        log.errorf(e, "[PlayerDataGrpcService] Unexpected failure in getPlayer() for %s — clearing state.", uuid);
                    }
                }).onFailure().call(e -> {
                    if (e instanceof StatusRuntimeException sre && sre.getStatus().getCode() == Status.Code.CANCELLED) {
                        return Uni.createFrom().voidItem();
                    }
                    return redisKey.del(stateKey).onFailure().recoverWithItem(0);
                })
                .onFailure().transform(e -> {
                    if (e instanceof StatusRuntimeException) {
                        return e;
                    }

                    return Status.UNAVAILABLE
                            .withCause(e)
                            .withDescription("Unable to get the player profile: " + e.getMessage())
                            .asRuntimeException();
                });
    }


    @Override
    public Uni<Empty> saveProfile(SaveProfileRequest req) {
        final String uuid = req.getUuid();
        final String dirtyKey = "player:dirty:" + uuid;

        log.debugf("[PlayerDataGrpcService] saveProfile() called for %s (%d component(s)).", uuid, req.getComponentsCount());

        return redisString.setex(dirtyKey, 20, "1")
                .chain(() -> redisStream.xadd(config.streamKey(), STREAM_ARGS, Map.of("save_profile_payload", req.toByteArray())))
                .invoke(id -> log.infof("[PlayerDataGrpcService] saveProfile() enqueued for %s (stream message id: %s).", uuid, id))
                .replaceWith(Empty.getDefaultInstance())
                .onFailure().invoke(e -> log.errorf(e, "[PlayerDataGrpcService] saveProfile() failed to enqueue for %s — releasing dirty key.", uuid))
                .onFailure().call(() -> redisKey.del(dirtyKey))
                .onFailure().transform(e -> Status.UNAVAILABLE.withCause(e).withDescription("Failed to enqueue the profile saving in Redis!").asRuntimeException());
    }

    @Override
    public Uni<Empty> saveComponent(SaveComponentRequest req) {
        final String uuid = req.getUuid();
        final String dirtyKey = "player:dirty:" + uuid;
        final String typeUrl = req.getComponent().getTypeUrl();

        log.debugf("[PlayerDataGrpcService] saveComponent() called for %s (type: %s).", uuid, typeUrl);

        return redisString.setex(dirtyKey, 20, "1").
                chain(() -> redisStream.xadd(config.streamKey(), STREAM_ARGS, Map.of("save_component_payload", req.toByteArray())))
                .invoke(id -> log.infof("[PlayerDataGrpcService] saveComponent() enqueued for %s — type: %s, stream id: %s.", uuid, typeUrl, id))
                .replaceWith(Empty.getDefaultInstance())
                .onFailure().invoke(e -> log.errorf(e, "[PlayerDataGrpcService] saveComponent() failed to enqueue for %s — releasing dirty key.", uuid))
                .onFailure().call(_ -> redisKey.del(dirtyKey))
                .onFailure().transform(e -> Status.UNAVAILABLE.withCause(e).withDescription("Failed to enqueue component saving in Redis!").asRuntimeException());
    }

    @Override
    public Uni<Empty> deleteComponent(DeleteComponentRequest req) {
        final String uuid = req.getUuid();
        final String dirtyKey = "player:dirty:" + uuid;
        final String url = req.getComponentUrl();

        //TODO: drop if component is not loaded inside dynamic registry

        log.debugf("[PlayerDataGrpcService] deleteComponent() called for %s (url: %s).", uuid, url);

        return redisString.setex(dirtyKey, 20, "1")
                .chain(() -> redisStream.xadd(config.streamKey(), STREAM_ARGS, Map.of("delete_component_payload", req.toByteArray())))
                .invoke(id -> log.infof("[PlayerDataGrpcService] deleteComponent() enqueued for %s — url: %s, stream id: %s.", uuid, url, id))
                .replaceWith(Empty.getDefaultInstance())
                .onFailure().invoke(e -> log.errorf(e, "[PlayerDataGrpcService] deleteComponent() failed to enqueue for %s — releasing dirty key.", uuid))
                .onFailure().call(() -> redisKey.del(dirtyKey))
                .onFailure().transform(e -> Status.UNAVAILABLE.withCause(e).withDescription("Failed to enqueue the deletion of component in Redis!").asRuntimeException());
    }

    @Override
    public Uni<Empty> freePlayer(PlayerRequest req) {
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
    public Uni<Empty> playerHeartBeatBatch(HeartBeatBatchRequest req) {
        if (req.getBeatCount() <= 0) return Uni.createFrom().item(Empty.getDefaultInstance());

        log.debugf("[PlayerDataGrpcService] Heartbeat batch received — renewing TTL for %d player(s).", req.getBeatCount());

        return Multi.createFrom().iterable(req.getBeatList())
                .onItem().transformToUniAndMerge(beat ->
                        redisKey.expire("player:state:" + beat.getUuid(), 30)
                                .invoke(exists -> {
                                    if (Boolean.FALSE.equals(exists)) {
                                        log.debugf("[PlayerDataGrpcService] Heartbeat: state key missing for %s (may be already FREE).", beat.getUuid());
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
    public Uni<Empty> playerHeartBeat(PlayerRequest req) {
        return playerHeartBeatBatch(HeartBeatBatchRequest.newBuilder().addBeat(req).build());
    }


    private Uni<PlayerResponse> readFromMongo(String uuid) {
        return playersCollection.find(Filters.eq("uuid", uuid)).collect().first()
                .ifNoItem().after(Duration.ofSeconds(5)).failWith(() -> new MongoTimeoutException("MongoDB response timeout for " + uuid))
                .onFailure(e -> e instanceof MongoSocketException || e instanceof MongoTimeoutException)
                .retry().withBackOff(Duration.ofMillis(200)).atMost(2)
                .onItem().transform(doc -> {
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
                        if (compDoc == null) continue;

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
                            log.warnf("[PlayerDataGrpcService] Unknown proto type '%s' for player %s — is the .desc file loaded?", shortType, uuid);
                            skipped++;
                        }
                    }

                    log.infof("[PlayerDataGrpcService] MongoDB read for %s: %d component(s) loaded, %d skipped.", uuid, loaded, skipped);
                    return res.build();
                })

                .onFailure().invoke(e -> log.errorf(e, "[PlayerDataGrpcService] Critical MongoDB read failure for %s.", uuid))
                .onFailure().transform(e -> Status.UNAVAILABLE.withCause(e).withDescription("MongoDB read failed!").asRuntimeException());
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
