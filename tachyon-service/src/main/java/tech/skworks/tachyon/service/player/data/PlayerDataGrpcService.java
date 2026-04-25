package tech.skworks.tachyon.service.player.data;

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
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import tech.skworks.tachyon.service.contracts.player.data.*;
import tech.skworks.tachyon.service.infra.DynamicProtobufRegistry;
import tech.skworks.tachyon.service.player.PlayerConfig;

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
    @Inject
    DynamicProtobufRegistry dynamicProtobufRegistry;
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
    public Uni<PullProfileResponse> pullProfile(PullProfileRequest request) {
        final String uuid = request.getUuid();
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
    public Uni<Empty> pushProfile(PushProfileRequest request) {
        final String uuid = request.getUuid();
        final String dirtyKey = "player:dirty:" + uuid;

        log.debugf("[PlayerDataGrpcService] saveProfile() called for %s (%d component(s) to saves, %d component(s) to remove).", uuid, request.getComponentsToSaveCount(), request.getComponentsToRemoveCount());


        try {
            for (String url : request.getComponentsToRemoveList()) assertComponentRegistered(url, uuid, "delete");
            for (Any any : request.getComponentsToSaveList()) assertComponentRegistered(any.getTypeUrl(), uuid, "save");
        } catch (StatusRuntimeException e) {
            return Uni.createFrom().failure(e);
        }


        return redisString.setex(dirtyKey, 20, "1")
                .chain(() -> redisStream.xadd(config.streamKey(), STREAM_ARGS, Map.of("save_profile_payload", request.toByteArray())))
                .invoke(id -> log.infof("[PlayerDataGrpcService] saveProfile() enqueued for %s (stream message id: %s).", uuid, id))
                .replaceWith(Empty.getDefaultInstance())
                .onFailure().invoke(e -> log.errorf(e, "[PlayerDataGrpcService] saveProfile() failed to enqueue for %s — releasing dirty key.", uuid))
                .onFailure().call(() -> redisKey.del(dirtyKey))
                .onFailure().transform(e -> {
                    if (e instanceof StatusRuntimeException) return e;
                    return Status.UNAVAILABLE.withCause(e).withDescription("Failed to enqueue the profile saving in Redis!").asRuntimeException();
                });
    }

    private Uni<PullProfileResponse> readFromMongo(String uuid) {
        return playersCollection.find(Filters.eq("uuid", uuid)).collect().first()
                .ifNoItem().after(Duration.ofSeconds(5)).failWith(() -> new MongoTimeoutException("MongoDB response timeout for " + uuid))
                .onFailure(e -> e instanceof MongoSocketException || e instanceof MongoTimeoutException)
                .retry().withBackOff(Duration.ofMillis(200)).atMost(2)
                .onItem().transform(doc -> {
                    PullProfileResponse.Builder response = PullProfileResponse.newBuilder().setUuid(uuid);

                    if (doc == null) {
                        log.infof("[PlayerDataGrpcService] No document found in MongoDB for %s — returning empty profile.", uuid);
                        return response.build();
                    }

                    if (!doc.containsKey("components")) {
                        log.debugf("[PlayerDataGrpcService] Document for %s has no 'components' field.", uuid);
                        return response.build();
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
                            response.addComponents(any);
                            loaded++;
                        } else {
                            log.warnf("[PlayerDataGrpcService] Unknown proto type '%s' for player %s — is the .desc file loaded?", shortType, uuid);
                            skipped++;
                        }
                    }

                    log.infof("[PlayerDataGrpcService] MongoDB read for %s: %d component(s) loaded, %d skipped.", uuid, loaded, skipped);
                    return response.build();
                })

                .onFailure().invoke(e -> log.errorf(e, "[PlayerDataGrpcService] Critical MongoDB read failure for %s.", uuid))
                .onFailure().transform(e -> Status.UNAVAILABLE.withCause(e).withDescription("MongoDB read failed!").asRuntimeException());
    }

    private Uni<PullProfileResponse> parseResponse(byte[] bytes) {
        return Uni.createFrom().item(() -> {
            try {
                return PullProfileResponse.parseFrom(bytes);
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse cached PlayerResponse", e);
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    private void assertComponentRegistered(String typeUrl, String uuid, String action) {
        typeUrl = PlayerDataStreamWorker.toShortType(typeUrl);
        if (dynamicProtobufRegistry.findDescriptor(typeUrl) == null) {
            log.errorf("Unable to %s component %s for player %s. The component is not registered inside the registry.", action, typeUrl, uuid);
            log.errorf("Loaded components: ");
            for (Descriptors.Descriptor loadedDescriptor : dynamicProtobufRegistry.getLoadedDescriptors()) {
                log.errorf("  - %s",loadedDescriptor.getFullName());
                log.errorf("  - %s",loadedDescriptor.getName());

            }
            throw Status.INVALID_ARGUMENT
                    .withDescription(String.format("Unable to find the component %s inside the service registry.", typeUrl))
                    .asRuntimeException();
        }
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
