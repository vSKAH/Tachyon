package tech.skworks.tachyon.service.player.data;

import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.model.Filters;
import io.grpc.*;
import io.grpc.stub.ServerCalls;
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
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import org.bson.BsonBinaryWriter;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.RawBsonDocument;
import org.bson.codecs.BsonDocumentCodec;
import org.bson.codecs.EncoderContext;
import org.bson.io.BasicOutputBuffer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import tech.skworks.tachyon.service.infra.RedisKeys;
import tech.skworks.tachyon.service.infra.grpc.BsonMarshaller;
import tech.skworks.tachyon.service.player.PlayerConfig;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;

/**
 * Project Tachyon
 * Class PlayerDataGrpcService
 * Pure BSON gRPC Service using BsonMarshaller
 */
@GrpcService
@NonBlocking
public class PlayerDataGrpcService implements BindableService {

    @Inject
    Logger log;
    @Inject
    PlayerConfig config;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String dbName;
    @Inject
    ReactiveMongoClient mongoClient;

    private ReactiveMongoCollection<RawBsonDocument> playersCollection;

    private static final XAddArgs STREAM_ARGS = new XAddArgs().maxlen(50000L).nearlyExactTrimming();
    private final ReactiveValueCommands<String, String> redisString;
    private final ReactiveValueCommands<String, byte[]> redisBytes;
    private final ReactiveStreamCommands<String, String, byte[]> redisStream;
    private final ReactiveKeyCommands<String> redisKey;

    public static final MethodDescriptor<RawBsonDocument, RawBsonDocument> PULL_PROFILE_METHOD =
            MethodDescriptor.<RawBsonDocument, RawBsonDocument>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName(MethodDescriptor.generateFullMethodName("tech.skworks.tachyon.PlayerDataService", "PullProfile"))
                    .setRequestMarshaller(BsonMarshaller.INSTANCE)
                    .setResponseMarshaller(BsonMarshaller.INSTANCE)
                    .build();

    public static final MethodDescriptor<RawBsonDocument, RawBsonDocument> PUSH_PROFILE_METHOD =
            MethodDescriptor.<RawBsonDocument, RawBsonDocument>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName(MethodDescriptor.generateFullMethodName("tech.skworks.tachyon.PlayerDataService", "PushProfile"))
                    .setRequestMarshaller(BsonMarshaller.INSTANCE)
                    .setResponseMarshaller(BsonMarshaller.INSTANCE)
                    .build();

    public PlayerDataGrpcService(ReactiveRedisDataSource redisDS) {
        this.redisString = redisDS.value(String.class);
        this.redisBytes = redisDS.value(byte[].class);
        this.redisStream = redisDS.stream(String.class, String.class, byte[].class);
        this.redisKey = redisDS.key();
    }

    @PostConstruct
    void init() {
        this.playersCollection = mongoClient.getDatabase(dbName).getCollection(config.collection(), RawBsonDocument.class);
    }

    @Override
    public ServerServiceDefinition bindService() {
        return ServerServiceDefinition.builder("tech.skworks.tachyon.PlayerDataService")
                .addMethod(PULL_PROFILE_METHOD, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
                    pullProfile(request).subscribe().with(
                            response -> {
                                responseObserver.onNext(response);
                                responseObserver.onCompleted();
                            },
                            error -> responseObserver.onError(error)
                    );
                }))
                .addMethod(PUSH_PROFILE_METHOD, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
                    pushProfile(request).subscribe().with(
                            response -> {
                                responseObserver.onNext(response);
                                responseObserver.onCompleted();
                            },
                            error -> responseObserver.onError(error)
                    );
                }))
                .build();
    }

    public Uni<RawBsonDocument> pullProfile(RawBsonDocument request) {
        final String uuid = request.getString("uuid").getValue();
        final String dirtyKey = RedisKeys.dirty(uuid);
        final String stateKey = RedisKeys.state(uuid);
        final String cacheKey = RedisKeys.cache(uuid);

        log.debugf("[PlayerDataGrpcService] pullProfile() called for %s.", uuid);

        return redisString.get(dirtyKey).chain(dirty -> {
            if (dirty != null) {
                log.infof("[PlayerDataGrpcService] pullProfile() for %s blocked — DATA_DIRTY (save in progress).", uuid);
                return Uni.createFrom().failure(Status.CANCELLED.withDescription("DATA_DIRTY: Player data is currently being saved (Player: " + uuid + ")").asRuntimeException());
            }

            return redisString.setAndChanged(stateKey, "USED", new SetArgs().nx().ex(RedisKeys.STATE_TTL_SECONDS)).chain(acquired -> {
                if (!acquired) {
                    log.infof("[PlayerDataGrpcService] pullProfile() for %s blocked — ALREADY_LOADED (active on another server).", uuid);
                    return Uni.createFrom().failure(Status.CANCELLED.withDescription("ALREADY_LOADED: Player data is currently active on another server (Player: " + uuid + ")").asRuntimeException());
                }

                log.debugf("[PlayerDataGrpcService] State set to USED for %s — checking cache...", uuid);

                return redisBytes.get(cacheKey).chain(cached -> {
                    if (cached != null) {
                        log.infof("[PlayerDataGrpcService] Cache HIT for %s (%d bytes).", uuid, cached.length);
                        return Uni.createFrom().item(new RawBsonDocument(cached));
                    }

                    log.infof("[PlayerDataGrpcService] Cache MISS for %s — reading from MongoDB.", uuid);
                    return readFromMongo(uuid)
                            .chain(response -> {
                                byte[] bytes = toByteArray(response);
                                log.infof("[PlayerDataGrpcService] MongoDB read successful for %s — caching (%d bytes).", uuid, bytes.length);
                                return redisBytes.setex(cacheKey, RedisKeys.CACHE_TTL_SECONDS, bytes).replaceWith(response);
                            })
                            .onFailure().invoke(() -> log.errorf("[PlayerDataGrpcService] MongoDB read failed for %s — releasing state.", uuid))
                            .onFailure().call(() -> redisKey.del(stateKey));
                });
            });
        }).onFailure().call(e -> {
            if (e instanceof StatusRuntimeException sre && sre.getStatus().getCode() == Status.Code.CANCELLED) {
                return Uni.createFrom().voidItem();
            }
            return redisKey.del(stateKey).onFailure().recoverWithItem(0);
        });
    }

    public Uni<RawBsonDocument> pushProfile(RawBsonDocument request) {
        final String uuid = request.getString("uuid").getValue();
        final String dirtyKey = RedisKeys.dirty(uuid);
        final byte[] payloadBytes = toByteArray(request);

        log.debugf("[PlayerDataGrpcService] pushProfile() enqueuing save for %s (%d bytes).", uuid, payloadBytes.length);

        return redisString.setex(dirtyKey, RedisKeys.DIRTY_TTL_SECONDS, "1")
                .chain(() -> redisStream.xadd(config.streamKey(), STREAM_ARGS, Map.of("save_profile_payload", payloadBytes)))
                .invoke(id -> log.infof("[PlayerDataGrpcService] saveProfile() enqueued for %s (stream message id: %s).", uuid, id))
                .replaceWith(emptyBsonResponse())
                .onFailure().invoke(e -> log.errorf(e, "[PlayerDataGrpcService] saveProfile() failed to enqueue for %s — releasing dirty key.", uuid))
                .onFailure().call(() -> redisKey.del(dirtyKey));
    }

    private Uni<RawBsonDocument> readFromMongo(String uuid) {
        return playersCollection.find(Filters.eq("uuid", uuid)).collect().first()
                .ifNoItem().after(Duration.ofSeconds(5)).failWith(() -> new MongoTimeoutException("MongoDB response timeout for " + uuid))
                .onFailure(e -> e instanceof MongoSocketException || e instanceof MongoTimeoutException)
                .retry().withBackOff(Duration.ofMillis(200)).atMost(2)
                .onItem().transform(doc -> {
                    if (doc == null) {
                        log.infof("[PlayerDataGrpcService] No document found in MongoDB for %s — returning empty profile.", uuid);
                        BsonDocument emptyResp = new BsonDocument("uuid", new BsonString(uuid)).append("components", new BsonDocument());
                        return new RawBsonDocument(bsonDocumentToBytes(emptyResp));
                    }

                    BsonDocument components = doc.containsKey("components") && doc.isDocument("components")
                            ? doc.getDocument("components")
                            : new BsonDocument();

                    BsonDocument responseDoc = new BsonDocument("uuid", new BsonString(uuid)).append("components", components);
                    return new RawBsonDocument(bsonDocumentToBytes(responseDoc));
                });
    }

    public static byte[] toByteArray(RawBsonDocument doc) {
        ByteBuffer nio = doc.getByteBuffer().asNIO().duplicate();
        byte[] bytes = new byte[nio.remaining()];
        nio.get(bytes);
        return bytes;
    }

    public static byte[] bsonDocumentToBytes(BsonDocument document) {
        BasicOutputBuffer buffer = new BasicOutputBuffer();
        try (BsonBinaryWriter writer = new BsonBinaryWriter(buffer)) {
            new BsonDocumentCodec().encode(writer, document, EncoderContext.builder().build());
        }
        return buffer.toByteArray();
    }

    private static RawBsonDocument emptyBsonResponse() {
        return new RawBsonDocument(bsonDocumentToBytes(new BsonDocument()));
    }
}
