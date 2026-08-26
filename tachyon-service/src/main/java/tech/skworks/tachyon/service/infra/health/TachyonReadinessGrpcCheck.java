package tech.skworks.tachyon.service.infra.health;

import io.grpc.BindableService;
import io.grpc.ServerServiceDefinition;
import io.grpc.stub.ServerCalls;
import io.quarkus.grpc.GrpcService;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.smallrye.common.annotation.NonBlocking;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.bson.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import tech.skworks.tachyon.common.contract.HealthContract;
import tech.skworks.tachyon.common.marshaller.BsonMarshaller;

/**
 * Project Tachyon
 * Class SystemGrpcService
 *
 * @author  Jimmy (vSKAH) - 08/04/2026
 * @version 2.0
 * @since 1.0.0-SNAPSHOT
 */
@GrpcService
@NonBlocking
public class TachyonReadinessGrpcCheck implements BindableService {

    @ConfigProperty(name = "quarkus.application.name", defaultValue = "Tachyon-Primary")
    String applicationName;

    @ConfigProperty(name = "quarkus.mongodb.database", defaultValue = "tachyon")
    String dbName;

    @Inject
    ReactiveMongoClient mongoClient;

    @Inject
    ReactiveRedisDataSource redisDS;

    private static final long HEALTH_CACHE_TTL_MS = 3000L;
    private volatile boolean cachedRedisUp = true;
    private volatile boolean cachedMongoUp = true;
    private volatile long lastCheckTime = 0L;

    @Override
    public ServerServiceDefinition bindService() {
        return ServerServiceDefinition.builder(HealthContract.SERVICE_NAME)
                .addMethod(HealthContract.PING_METHOD, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
                    ping(request).subscribe().with(
                            response -> {
                                responseObserver.onNext(response);
                                responseObserver.onCompleted();
                            },
                            responseObserver::onError
                    );
                }))
                .build();
    }

    public Uni<RawBsonDocument> ping(RawBsonDocument rawRequest) {
        long now = System.currentTimeMillis();

        if (now - lastCheckTime < HEALTH_CACHE_TTL_MS) {
            return Uni.createFrom().item(buildPingResponse(cachedRedisUp, cachedMongoUp, rawRequest.getInt64("client_time", new BsonInt64(-1)).getValue(), now));
        }

        return Uni.combine().all().unis(checkRedis(), checkMongo()).asTuple()
                .invoke(tuple -> {
                    this.cachedRedisUp = tuple.getItem1();
                    this.cachedMongoUp = tuple.getItem2();
                    this.lastCheckTime = System.currentTimeMillis();
                })
                .map(tuple -> buildPingResponse(tuple.getItem1(), tuple.getItem2(), rawRequest.getInt64("client_time", new BsonInt64(-1)).getValue(), System.currentTimeMillis()));
    }

    private Uni<Boolean> checkRedis() {
        return redisDS.execute("PING")
                .map(_ -> true)
                .onFailure().recoverWithItem(false);
    }

    private Uni<Boolean> checkMongo() {
        return mongoClient.getDatabase(dbName).runCommand(new Document("ping", 1))
                .map(_ -> true)
                .onFailure().recoverWithItem(false);
    }

    private RawBsonDocument buildPingResponse(boolean redisUp, boolean mongoUp, long clientTime, long serverTime) {
        boolean healthy = redisUp && mongoUp;

        BsonDocument response = new BsonDocument()
                .append("client_time", new BsonInt64(clientTime))
                .append("server_time", new BsonInt64(serverTime))
                .append("tachyon_server_name", new BsonString(applicationName))
                .append("redis_online", new BsonBoolean(redisUp))
                .append("mongo_online", new BsonBoolean(mongoUp))
                .append("healthy", new BsonBoolean(healthy));

        return BsonMarshaller.toRawBsonDocument(response);
    }
}
