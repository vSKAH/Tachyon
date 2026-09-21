package tech.skworks.tachyon.service.player.data;

import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import io.grpc.*;
import io.grpc.stub.ServerCalls;
import io.quarkus.grpc.GrpcService;
import io.quarkus.mongodb.FindOptions;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.quarkus.mongodb.reactive.ReactiveMongoCollection;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.quarkus.redis.datasource.stream.ReactiveStreamCommands;
import io.quarkus.redis.datasource.stream.XAddArgs;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.quarkus.redis.datasource.value.SetArgs;
import io.smallrye.common.annotation.NonBlocking;
import io.smallrye.common.constraint.NotNull;
import io.smallrye.mutiny.Uni;
import io.vertx.redis.client.Command;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.RawBsonDocument;
import org.bson.conversions.Bson;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import tech.skworks.tachyon.common.BsonUtil;
import tech.skworks.tachyon.common.contract.PlayerDataContract;
import tech.skworks.tachyon.common.types.Empty;
import tech.skworks.tachyon.service.infra.health.TachyonWarmupService;
import tech.skworks.tachyon.service.player.PlayerConfig;
import tech.skworks.tachyon.service.player.RedisKeys;
import tech.skworks.tachyon.service.player.RedisScripts;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;

/**
 * Project Tachyon
 * Class PlayerDataGrpcService
 * Pure BSON gRPC Service using BsonMarshaller
 */
@GrpcService
@NonBlocking
public class PlayerDataGrpcService implements BindableService {

    private static final XAddArgs STREAM_ARGS = new XAddArgs().maxlen(50000L).nearlyExactTrimming();
    private static final Bson PULL_PROFILE_PROJECTION = Projections.fields(Projections.include(PlayerDataContract.FIELD_UUID, PlayerDataContract.FIELD_COMPONENTS, PlayerDataContract.FIELD_VERSIONS), Projections.excludeId());
    public static final FindOptions PULL_PROFILE_FIND_OPTIONS = new FindOptions().projection(PlayerDataGrpcService.PULL_PROFILE_PROJECTION);

    @Inject
    Logger log;
    @Inject
    PlayerConfig config;

    @Inject
    TachyonWarmupService warmupService;

    @ConfigProperty(name = "quarkus.mongodb.database", defaultValue = "tachyon")
    String dbName;
    @Inject
    ReactiveMongoClient mongoClient;
    @Inject
    ReactiveRedisDataSource redis;

    private ReactiveMongoCollection<RawBsonDocument> playersCollection;

    private ReactiveValueCommands<String, String> redisString;
    private ReactiveValueCommands<String, byte[]> redisBytes;
    private ReactiveStreamCommands<String, String, byte[]> redisStream;
    private ReactiveKeyCommands<String> redisKey;

    @PostConstruct
    void init() {
        this.redisString = redis.value(String.class);
        this.redisBytes = redis.value(byte[].class);
        this.redisStream = redis.stream(String.class, String.class, byte[].class);
        this.redisKey = redis.key();
        this.playersCollection = mongoClient.getDatabase(dbName).getCollection(config.collection(), RawBsonDocument.class);
    }

    @Override
    public ServerServiceDefinition bindService() {
        return ServerServiceDefinition.builder(PlayerDataContract.SERVICE_NAME)
                .addMethod(PlayerDataContract.PULL_PROFILE_METHOD, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
                    pullProfile(request).subscribe().with(
                            response -> {
                                responseObserver.onNext(response);
                                responseObserver.onCompleted();
                            },
                            responseObserver::onError
                    );
                }))
                .addMethod(PlayerDataContract.PUSH_PROFILE_METHOD, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
                    pushProfile(request).subscribe().with(
                            response -> {
                                responseObserver.onNext(response);
                                responseObserver.onCompleted();
                            },
                            responseObserver::onError
                    );
                }))
                .addMethod(PlayerDataContract.PUSH_DLQ_BATCH_METHOD, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
                    pushDLQBatch(request).subscribe().with(
                            response -> {
                                responseObserver.onNext(response);
                                responseObserver.onCompleted();
                            },
                            responseObserver::onError
                    );
                }))
                .build();
    }

    private Uni<RawBsonDocument> pullProfile(final @NotNull RawBsonDocument requestDocument) {
        Objects.requireNonNull(requestDocument, "requestDocument cannot be null.");

        if (!requestDocument.containsKey("uuid") || !requestDocument.isString("uuid")) {
            return Uni.createFrom().failure(Status.INVALID_ARGUMENT.withDescription("Missing or invalid 'uuid' field in request").asRuntimeException());
        }

        if (!requestDocument.containsKey("server_id") || !requestDocument.isString("server_id")) {
            return Uni.createFrom().failure(Status.INVALID_ARGUMENT.withDescription("Missing or invalid 'server_id' field in request").asRuntimeException());
        }

        final var uuid = requestDocument.getString("uuid").getValue();
        final var serverId = requestDocument.getString("server_id").getValue();

        if (warmupService.isWarmingUp()) {
            long remainingSec = (warmupService.getRemainingWarmupMs() / 1000) + 1;
            log.infof("[PlayerDataGrpcService] pullProfile(%s, server=%s) BLOCKED — Backend is in warm-up (%ds remaining).", uuid, serverId, remainingSec);

            return Uni.createFrom().failure(Status.ABORTED.withDescription("BACKEND_WARMUP: Recovery in progress. Retry in " + remainingSec + " seconds").asRuntimeException());
        }

        final var dirtyKey = RedisKeys.dirty(uuid);
        final var serverKey = RedisKeys.server(uuid);
        final var cacheKey = RedisKeys.cache(uuid);

        log.debugf("[PlayerDataGrpcService] pullProfile() called for %s.", uuid);

        return redis.execute(Command.EVAL,
                RedisScripts.PULL_PROFILE_LUA,
                "2", dirtyKey, serverKey,
                serverId, RedisKeys.STATE_TTL_SECONDS_STRING)
                .chain(response -> {
                    final var status = response.get(0).toString();
                    final var owner = response.get(1).toString();

                    return switch (status) {
                        case "ACQUIRED" -> {
                            log.debugf("[PlayerDataGrpcService] Ownership acquired for %s by '%s'.", uuid, serverId);
                            yield loadPlayerData(cacheKey, uuid, serverKey);
                        }
                        case "SAME_SERVER" -> {
                            log.debugf("[PlayerDataGrpcService] Reconnect on same server '%s' for %s.", serverId, uuid);
                            yield loadPlayerData(cacheKey, uuid, serverKey);
                        }
                        case "DIRTY" -> {
                            log.infof("[PlayerDataGrpcService] pullProfile(%s) blocked — DATA_DIRTY.", uuid);
                            yield Uni.createFrom().failure(Status.ABORTED.withDescription("DATA_DIRTY: Save in progress").asRuntimeException());
                        }
                        case "HANDOFF" -> {
                            log.infof("[PlayerDataGrpcService] Player %s active on '%s' (requested by '%s') — Handoff required.", uuid, owner, serverId);
                            yield Uni.createFrom().failure(Status.ABORTED.withDescription("ALREADY_LOADED: Active on " + owner).asRuntimeException());
                        }
                        default -> Uni.createFrom().failure(Status.INTERNAL.withDescription("Unknown Lua status: " + status).asRuntimeException());
                    };
                }).onFailure().call(exception -> {
                    if (exception instanceof StatusRuntimeException sre && sre.getStatus().getCode() == Status.Code.ABORTED) {
                        return Uni.createFrom().voidItem();
                    }
                    return redisKey.del(serverKey).replaceWithVoid().onFailure().recoverWithNull();
                });
    }

    public Uni<RawBsonDocument> loadPlayerData(@NotNull final String cacheKey,
                                               @NotNull final String uuid,
                                               @NotNull final String serverKey) {
        return redisBytes.get(cacheKey).chain(cached -> {
            if (cached != null) {
                log.infof("[PlayerDataGrpcService] Cache HIT for %s (%d bytes).", uuid, cached.length);
                return Uni.createFrom().item(new RawBsonDocument(cached));
            }

            log.infof("[PlayerDataGrpcService] Cache MISS for %s — reading from MongoDB.", uuid);
            return readFromMongo(uuid)
                    .chain(response -> {

                        boolean hasData = response.containsKey(PlayerDataContract.FIELD_COMPONENTS)
                                && response.isDocument(PlayerDataContract.FIELD_COMPONENTS)
                                && !response.getDocument(PlayerDataContract.FIELD_COMPONENTS).isEmpty();

                        if (!hasData) {
                            log.infof("[PlayerDataGrpcService] New player %s has no components — skipping cache.", uuid);
                            return Uni.createFrom().item(response);
                        }

                        byte[] bytes = BsonUtil.toByteArray(response);
                        log.infof("[PlayerDataGrpcService] MongoDB read successful for %s — caching (%d bytes).", uuid, bytes.length);
                        return redisBytes.setex(cacheKey, RedisKeys.CACHE_TTL_SECONDS, bytes).replaceWith(response);
                    })
                    .onFailure().invoke(() -> log.errorf("[PlayerDataGrpcService] MongoDB read failed for %s — releasing state.", uuid))
                    .onFailure().call(() -> redisKey.del(serverKey));
        });
    }

    private Uni<Empty> pushProfile(final @NotNull RawBsonDocument requestDocument) {
        Objects.requireNonNull(requestDocument, "requestDocument cannot be null.");

        if (!requestDocument.containsKey("uuid") || !requestDocument.isString("uuid")) {
            return Uni.createFrom().failure(Status.INVALID_ARGUMENT.withDescription("Missing or invalid 'uuid' field in request").asRuntimeException());
        }

        final var uuid = requestDocument.getString("uuid").getValue();
        final var dirtyKey = RedisKeys.dirty(uuid);
        final var requestDocumentBytes = BsonUtil.toByteArray(requestDocument);

        log.debugf("[PlayerDataGrpcService] pushProfile() enqueuing save for %s (%d bytes).", uuid, requestDocumentBytes.length);

        return redis.execute(Command.EVAL, RedisScripts.DIRTY_INCREMENT_LUA, "1", dirtyKey, RedisKeys.DIRTY_TTL_SECONDS_STRING)
                .chain(() -> redisStream.xadd(config.streamKey(), STREAM_ARGS, Map.of("save_profile_payload", requestDocumentBytes)))
                .invoke(id -> log.infof("[PlayerDataGrpcService] saveProfile() enqueued for %s (stream message id: %s).", uuid, id))
                .replaceWith(Empty.INSTANCE)
                .onFailure().invoke(exception -> log.errorf(exception, "[PlayerDataGrpcService] saveProfile() failed to enqueue for %s — decrement dirty key counter.", uuid))
                .onFailure().call(() -> redis.execute(Command.EVAL, RedisScripts.DIRTY_BATCH_DECREMENT_LUA, "1", dirtyKey, RedisKeys.DIRTY_TTL_SECONDS_STRING))
                .onFailure().transform(e -> Status.UNAVAILABLE.withCause(e).withDescription("Failed to enqueue profile into redis stream").asRuntimeException());
    }

    private Uni<Empty> pushDLQBatch(final @NotNull RawBsonDocument requestDocument) {
        Objects.requireNonNull(requestDocument, "requestDocument cannot be null.");

        if (!requestDocument.containsKey("profiles") || !requestDocument.isArray("profiles")) {
            return Uni.createFrom().failure(Status.INVALID_ARGUMENT.withDescription("Missing or invalid 'profiles' field in request").asRuntimeException());
        }

        final var profilesArray = requestDocument.getArray("profiles");
        if (profilesArray.isEmpty()) {
            return Uni.createFrom().failure(Status.INVALID_ARGUMENT.withDescription("Empty 'profiles' field in request").asRuntimeException());
        }

        final var profilesUuids = new ArrayList<String>(profilesArray.size());
        final var profilePayloads = new ArrayList<byte[]>(profilesArray.size());

        for (final var bsonValue : profilesArray) {
            if (!bsonValue.isDocument()) continue;
            final var document = bsonValue.asDocument();
            final var uuidField = PlayerDataContract.FIELD_UUID;
            if (!document.containsKey(uuidField)) continue;
            final var uuid = document.getString(uuidField).getValue();
            profilesUuids.add(uuid);
            profilePayloads.add(BsonUtil.toByteArray(document));
        }

        if (profilesUuids.isEmpty()) {
            return Uni.createFrom().failure(Status.INVALID_ARGUMENT.withDescription("Empty 'profiles' field in request").asRuntimeException());
        }

        final var dirtyKeys = new ArrayList<String>(profilesUuids.size() + 3);
        dirtyKeys.add(RedisScripts.DIRTY_BATCH_INCREMENT_LUA);
        dirtyKeys.add(String.valueOf(profilesUuids.size()));
        for (final var uuid : profilesUuids) {
            dirtyKeys.add(RedisKeys.dirty(uuid));
        }
        dirtyKeys.add(RedisKeys.DIRTY_TTL_SECONDS_STRING);

        log.infof("[PlayerDataGrpcService] DLQ Batch received for %d players(s). Adding to stream...", profilesUuids.size());
        return redis.execute(Command.EVAL, dirtyKeys.toArray(String[]::new)).chain(() -> {
                    final var streamContent = new ArrayList<Uni<String>>(profilePayloads.size());
                    for (final var profilePayload : profilePayloads) {
                        streamContent.add(redisStream.xadd(config.streamKey(), STREAM_ARGS, Map.of("save_profile_payload", profilePayload)));
                    }
                    return Uni.combine().all().unis(streamContent).discardItems();
                }).replaceWith(Empty.INSTANCE)
                .onFailure().invoke(e -> log.errorf(e, "[PlayerDataGrpcService] Failed to process DLQ batch for %d player(s).", profilesUuids.size()))
                .onFailure().call(() -> {
                    final var rollbackKeys = new ArrayList<String>(dirtyKeys.size());
                    rollbackKeys.add(RedisScripts.DIRTY_BATCH_DECREMENT_LUA);
                    rollbackKeys.addAll(dirtyKeys.subList(1, dirtyKeys.size()));
                    return redis.execute(Command.EVAL, rollbackKeys.toArray(String[]::new)).replaceWithVoid().onFailure().recoverWithNull();
                })
                .onFailure().transform(e -> Status.UNAVAILABLE.withCause(e).withDescription("Failed to process DLQ batch in redis").asRuntimeException());
    }

    private Uni<RawBsonDocument> readFromMongo(final @NotNull String uuid) {
        Objects.requireNonNull(uuid, "uuid must not be null");
        final var uuidField = PlayerDataContract.FIELD_UUID;
        final var componentsField = PlayerDataContract.FIELD_COMPONENTS;
        final var versionsField = PlayerDataContract.FIELD_VERSIONS;

        return playersCollection.find(Filters.eq(uuidField, uuid), PULL_PROFILE_FIND_OPTIONS).collect().first()
                .ifNoItem().after(Duration.ofSeconds(5)).failWith(() -> new MongoTimeoutException("MongoDB response timeout for " + uuid))
                .onFailure(e -> e instanceof MongoSocketException || e instanceof MongoTimeoutException)
                .retry().withBackOff(Duration.ofMillis(200)).atMost(2)
                .onItem().transform(foundDocument -> {
                    if (foundDocument == null) {
                        log.infof("[PlayerDataGrpcService] No document found in MongoDB for %s — returning empty profile.", uuid);
                        final var emptyResponseDocument = new BsonDocument(uuidField, new BsonString(uuid))
                                .append(componentsField, new BsonDocument())
                                .append(versionsField, new BsonDocument());
                        return BsonUtil.toRawBsonDocument(emptyResponseDocument);
                    }


                    final var componentsDocument = foundDocument.containsKey(componentsField) && foundDocument.isDocument(componentsField)
                            ? foundDocument.getDocument(componentsField)
                            : new BsonDocument();

                    final var versionsDocument = foundDocument.containsKey(versionsField) && foundDocument.isDocument(versionsField) ? foundDocument.getDocument(versionsField) : new BsonDocument();

                    final var responseDocument = new BsonDocument(uuidField, new BsonString(uuid))
                            .append(componentsField, componentsDocument)
                            .append(versionsField, versionsDocument);
                    return BsonUtil.toRawBsonDocument(responseDocument);
                });
    }

}
