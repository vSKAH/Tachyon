package tech.skworks.tachyon.service.player.handoff;

import io.grpc.BindableService;
import io.grpc.ServerServiceDefinition;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import io.quarkus.grpc.GrpcService;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.quarkus.redis.datasource.pubsub.ReactivePubSubCommands;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.common.annotation.NonBlocking;
import io.smallrye.common.constraint.NotNull;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.RawBsonDocument;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import tech.skworks.tachyon.common.BsonUtil;
import tech.skworks.tachyon.common.contract.HandoffContract;
import tech.skworks.tachyon.service.player.RedisKeys;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short class explaination
 * <p>
 * Long class explaination with @link if needed
 * </p>
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @author Jimmy (vSKAH) - 21/09/2026
 * @version 1.0
 * @since 2.0.0-alpha.2
 */
@GrpcService
@Singleton
@NonBlocking
public class HandoffGrpcService implements BindableService {

    @Inject
    Logger log;

    @Inject
    ReactiveRedisDataSource redis;

    @ConfigProperty(name = "quarkus.application.name")
    String applicationName;

    private ReactiveValueCommands<String, String> redisString;
    private ReactivePubSubCommands<byte[]> redisBytesPubSub;
    private ReactiveKeyCommands<String> redisKey;

    //All the grpc streams opened: key = server_id
    private final Map<String, StreamObserver<RawBsonDocument>> serverStreams = new ConcurrentHashMap<>();

    //key uuid
    //TODO: Uses reals uuid
    private final Map<String, CompletableFuture<RawBsonDocument>> pendingLocalEvictions = new ConcurrentHashMap<>();

    private final Map<UUID, CompletableFuture<RawBsonDocument>> pendingCrossPodEvictions = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        this.redisString = redis.value(String.class);
        this.redisBytesPubSub = redis.pubsub(byte[].class);
        this.redisKey = redis.key();

        final String channel = RedisKeys.handoffPodChannel(applicationName);
        redisBytesPubSub.subscribe(channel, this::handleCrossPodMessage)
                .subscribe().with(
                        _ -> log.infof("[HandoffGrpcService] Initialized Pod '%s' and subscribed to cross-pod channel '%s'.", applicationName, channel),
                        error -> log.errorf(error, "[HandoffGrpcService] Failed to subscribe to cross-pod channel '%s'.", channel)
                );
    }

    @Override
    public ServerServiceDefinition bindService() {
        return ServerServiceDefinition.builder(HandoffContract.SERVICE_NAME)
                .addMethod(HandoffContract.HANDOFF_STREAM_METHOD, ServerCalls.asyncBidiStreamingCall(observer ->
                        new HandoffSessionController(this, observer)))
                .build();
    }


    public void onServerConnected(@NotNull final String serverId,
                                  @NotNull final StreamObserver<RawBsonDocument> responseObserver) {
        serverStreams.put(serverId, responseObserver);
        redisString.setex(RedisKeys.handoffServer(serverId),
                        RedisKeys.HANDOFF_SERVER_TTL_SECONDS,
                        applicationName
                ).subscribe().with(
                        _ -> log.infof("[HandoffGrpcService] Server '%s' registered on Pod '%s' (Redis entry set).", serverId, applicationName),
                        e -> log.errorf(e, "[HandoffGrpcService] Failed to write Redis location for '%s'.", serverId)
                );
    }

    public void onEvictionAck(@NotNull final RawBsonDocument document) {
        if (!document.containsKey(HandoffContract.FIELD_UUID) || !document.isString(HandoffContract.FIELD_UUID)) {
            log.warn("[HandoffGrpcService] Received EVICT_ACK without valid 'uuid'.");
            return;
        }
        final String uuid = document.getString(HandoffContract.FIELD_UUID).getValue();
        final CompletableFuture<RawBsonDocument> future = pendingLocalEvictions.remove(uuid);
        if (future != null) {
            future.complete(document);
        } else {
            log.warnf("[HandoffGrpcService] Received orphan EVICT_ACK for %s (timed out or not requested).", uuid);
        }
    }

    public void onServerDisconnected(@NotNull final String serverId,
                                     @NotNull final StreamObserver<RawBsonDocument> responseObserver) {
        if (serverId != null) {
            serverStreams.remove(serverId, responseObserver);
            redisKey.del(RedisKeys.handoffServer(serverId))
                    .subscribe().with(
                            _ -> log.debugf("[HandoffGrpcService] Cleared Redis location for server '%s'.", serverId),
                            e -> log.warnf(e, "[HandoffGrpcService] Failed to clear Redis location for server '%s'.", serverId)
                    );
        }
    }

    @Scheduled(every = "20s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void refreshServerLocations() {
        for (final String serverId : serverStreams.keySet()) {
            redisString.setex(RedisKeys.handoffServer(serverId), RedisKeys.HANDOFF_SERVER_TTL_SECONDS, applicationName)
                    .subscribe().with(
                            _ -> log.debugf("[HandoffGrpcService] Location refreshed for server '%s'.", serverId),
                            e -> log.warnf(e, "[HandoffGrpcService] Failed to refresh location for '%s'.", serverId)
                    );
        }
    }

    /**
     * @return Uni with the RawBsonDocument of response EVICT_ACK (or null if timeout/error).
     */
    public Uni<RawBsonDocument> requestEviction(@NotNull final String sourceServer,
                                                @NotNull final String uuid) {
        Objects.requireNonNull(sourceServer, "sourceServer cannot be null");
        Objects.requireNonNull(uuid, "uuid cannot be null");

        if (serverStreams.containsKey(sourceServer)) {
            log.debugf("[HandoffGrpcService] Fast path eviction for %s on local server '%s'.", uuid, sourceServer);
            return requestEvictionLocal(sourceServer, uuid);
        }

        log.infof("[HandoffGrpcService] Server '%s' is not on this pod — querying Redis for owner pod...", sourceServer);
        return redisString.get(RedisKeys.handoffServer(sourceServer)).chain(targetPodId -> {

            if (targetPodId == null) {
                log.warnf("[HandoffGrpcService] No pod registered in Redis for server '%s' — cannot evict %s.", sourceServer, uuid);
                return Uni.createFrom().nullItem();
            }

            if (applicationName.equals(targetPodId)) {
                log.warnf("[HandoffGrpcService] Stale Redis registration for '%s' pointing to this pod.", sourceServer);
                return Uni.createFrom().nullItem();
            }

            final var correlationId = UUID.randomUUID();
            final var correlationIdString = correlationId.toString();
            final var future = new CompletableFuture<RawBsonDocument>();

            pendingCrossPodEvictions.put(correlationId, future);

            final var requestDoc = new BsonDocument()
                    .append(HandoffContract.FIELD_ACTION, new BsonString("CROSS_POD_EVICT_REQUEST"))
                    .append("correlation_id", new BsonString(correlationIdString))
                    .append(HandoffContract.FIELD_SERVER_ID, new BsonString(sourceServer))
                    .append(HandoffContract.FIELD_UUID, new BsonString(uuid))
                    .append("reply_pod", new BsonString(applicationName));

            final byte[] payload = BsonUtil.toByteArray(requestDoc);
            final String targetChannel = RedisKeys.handoffPodChannel(targetPodId);

            log.infof("[HandoffGrpcService] Forwarding eviction for %s to target Pod '%s' (channel: %s)...", uuid, targetPodId, targetChannel);

            return redisBytesPubSub.publish(targetChannel, payload)
                    .chain(() -> Uni.createFrom().completionStage(future))
                    .ifNoItem().after(Duration.ofMillis(2000)).fail()
                    .onFailure().invoke(() -> pendingCrossPodEvictions.remove(correlationId))
                    .onFailure().recoverWithNull();
        });
    }


    private Uni<RawBsonDocument> requestEvictionLocal(@NotNull final String sourceServer,
                                                      @NotNull final String uuid) {
        final StreamObserver<RawBsonDocument> stream = serverStreams.get(sourceServer);
        if (stream == null) {
            log.warnf("[HandoffGrpcService] Cannot evict %s: no active local stream for server '%s'.", uuid, sourceServer);
            return Uni.createFrom().nullItem();
        }

        final CompletableFuture<RawBsonDocument> future = new CompletableFuture<>();
        pendingLocalEvictions.put(uuid, future);

        final var evictCommand = new BsonDocument()
                .append(HandoffContract.FIELD_ACTION, new BsonString(HandoffContract.ACTION_EVICT))
                .append(HandoffContract.FIELD_UUID, new BsonString(uuid));

        try {
            stream.onNext(BsonUtil.toRawBsonDocument(evictCommand));
            log.infof("[HandoffGrpcService] Sent local EVICT order for %s to server '%s'.", uuid, sourceServer);
        } catch (Exception e) {
            pendingLocalEvictions.remove(uuid);
            log.errorf(e, "[HandoffGrpcService] Failed to send local EVICT order to '%s'.", sourceServer);
            return Uni.createFrom().nullItem();
        }

        return Uni.createFrom().completionStage(future)
                .ifNoItem().after(Duration.ofMillis(2000)).fail()
                .onFailure().invoke(() -> pendingLocalEvictions.remove(uuid))
                .onFailure().recoverWithNull();
    }


    private void handleCrossPodMessage(final byte[] bytes) {
        if (bytes == null || bytes.length == 0) return;

        try {
            final RawBsonDocument doc = new RawBsonDocument(bytes);
            if (!doc.containsKey(HandoffContract.FIELD_ACTION) || !doc.isString(HandoffContract.FIELD_ACTION)) {
                return;
            }

            final String action = doc.getString(HandoffContract.FIELD_ACTION).getValue();

            switch (action) {
                case "CROSS_POD_EVICT_REQUEST" -> {
                    final String correlationId = doc.getString("correlation_id").getValue();
                    final String sourceServer = doc.getString(HandoffContract.FIELD_SERVER_ID).getValue();
                    final String uuid = doc.getString(HandoffContract.FIELD_UUID).getValue();
                    final String replyPod = doc.getString("reply_pod").getValue();

                    log.infof("[HandoffGrpcService] Received cross-pod eviction request for %s on '%s' (from Pod '%s').", uuid, sourceServer, replyPod);

                    requestEvictionLocal(sourceServer, uuid).subscribe().with(
                            evictAck -> {
                                final var responseDoc = new BsonDocument()
                                        .append(HandoffContract.FIELD_ACTION, new BsonString("CROSS_POD_EVICT_RESPONSE"))
                                        .append("correlation_id", new BsonString(correlationId))
                                        .append(HandoffContract.FIELD_UUID, new BsonString(uuid));

                                if (evictAck != null && evictAck.containsKey(HandoffContract.FIELD_PAYLOAD)) {
                                    responseDoc.append(HandoffContract.FIELD_PAYLOAD, evictAck.getDocument(HandoffContract.FIELD_PAYLOAD));
                                }

                                final String replyChannel = RedisKeys.handoffPodChannel(replyPod);
                                redisBytesPubSub.publish(replyChannel, BsonUtil.toByteArray(responseDoc)).subscribe().with(
                                        _ -> log.debugf("[HandoffGrpcService] Dispatched cross-pod eviction reply for %s to '%s'.", uuid, replyPod),
                                        e -> log.errorf(e, "[HandoffGrpcService] Failed to publish cross-pod eviction reply to '%s'.", replyPod)
                                );
                            },
                            error -> log.errorf(error, "[HandoffGrpcService] Error while executing local eviction for cross-pod request %s.", uuid)
                    );
                }

                case "CROSS_POD_EVICT_RESPONSE" -> {
                    final String correlationId = doc.getString("correlation_id").getValue();
                    final CompletableFuture<RawBsonDocument> future = pendingCrossPodEvictions.remove(UUID.fromString(correlationId));
                    if (future != null) {
                        future.complete(doc);
                    } else {
                        log.warnf("[HandoffGrpcService] Received orphan CROSS_POD_EVICT_RESPONSE (correlation: %s).", correlationId);
                    }
                }

                default -> log.warnf("[HandoffGrpcService] Unknown cross-pod action '%s'.", action);
            }
        } catch (Exception e) {
            log.error("[HandoffGrpcService] Failed to parse and handle cross-pod message.", e);
        }
    }

    public String getApplicationName() {
        return applicationName;
    }

    public boolean isServerConnected(@NotNull final String serverId) {
        return serverStreams.containsKey(serverId);
    }
}
