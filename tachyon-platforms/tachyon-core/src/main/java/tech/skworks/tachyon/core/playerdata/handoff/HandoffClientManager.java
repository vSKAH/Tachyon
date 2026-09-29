package tech.skworks.tachyon.core.playerdata.handoff;

import io.grpc.CallOptions;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.StreamObserver;
import org.bson.BsonArray;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.RawBsonDocument;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.skworks.tachyon.api.profile.TachyonProfile;
import tech.skworks.tachyon.api.profile.TachyonProfileRegistry;
import tech.skworks.tachyon.common.BsonUtil;
import tech.skworks.tachyon.common.contract.HandoffContract;
import tech.skworks.tachyon.common.contract.PlayerDataContract;
import tech.skworks.tachyon.core.grpc.GrpcClient;
import tech.skworks.tachyon.core.playerdata.PlayerDeltaAdapter;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
public class HandoffClientManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tachyon/HandoffClient");
    private static final long INITIAL_RECONNECT_DELAY_MS = 1000L;
    private static final long MAX_RECONNECT_DELAY_MS = 10000L;

    private final String serverId;
    private final GrpcClient grpcClient;
    private final TachyonProfileRegistry profileRegistry;
    private final ScheduledExecutorService reconnectExecutor;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private volatile StreamObserver<RawBsonDocument> requestObserver;
    private long currentReconnectDelayMs = INITIAL_RECONNECT_DELAY_MS;

    public HandoffClientManager(
            @NotNull final String serverId,
            @NotNull final GrpcClient grpcClient,
            @NotNull final TachyonProfileRegistry profileRegistry
    ) {
        this.serverId = Objects.requireNonNull(serverId, "serverId cannot be null");
        this.grpcClient = Objects.requireNonNull(grpcClient, "grpcClient cannot be null");
        this.profileRegistry = Objects.requireNonNull(profileRegistry, "profileRegistry cannot be null");
        this.reconnectExecutor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("tachyon-handoff-reconnect").factory()
        );
    }


    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        LOGGER.info("Starting Handoff control stream client for server '{}'...", serverId);
        connect();
    }

    private synchronized void connect() {
        if (!running.get()) {
            return;
        }

        try {
            final StreamObserver<RawBsonDocument> responseObserver = new StreamObserver<>() {
                @Override
                public void onNext(final RawBsonDocument document) {
                    handleServerMessage(document);
                }

                @Override
                public void onError(final Throwable t) {
                    connected.set(false);
                    requestObserver = null;
                    LOGGER.warn("Handoff control stream disconnected: {}. Scheduling reconnect...", t.getMessage());
                    scheduleReconnect();
                }

                @Override
                public void onCompleted() {
                    connected.set(false);
                    requestObserver = null;
                    LOGGER.info("Handoff control stream completed by backend. Scheduling reconnect...");
                    scheduleReconnect();
                }
            };

            this.requestObserver = ClientCalls.asyncBidiStreamingCall(
                    grpcClient.getChannel().newCall(HandoffContract.HANDOFF_STREAM_METHOD, CallOptions.DEFAULT),
                    responseObserver
            );

            final var registerDoc = new BsonDocument(HandoffContract.FIELD_ACTION, new BsonString(HandoffContract.ACTION_REGISTER))
                    .append(HandoffContract.FIELD_SERVER_ID, new BsonString(serverId));

            this.requestObserver.onNext(BsonUtil.toRawBsonDocument(registerDoc));
            LOGGER.debug("Sent REGISTER for '{}' on Handoff control stream.", serverId);

        } catch (Exception e) {
            connected.set(false);
            requestObserver = null;
            LOGGER.error("Failed to establish Handoff control stream with backend: {}", e.getMessage());
            scheduleReconnect();
        }
    }

    private void handleServerMessage(@NotNull final RawBsonDocument document) {
        if (document == null || !document.containsKey(HandoffContract.FIELD_ACTION) || !document.isString(HandoffContract.FIELD_ACTION)) {
            return;
        }

        final String action = document.getString(HandoffContract.FIELD_ACTION).getValue();

        switch (action) {
            case HandoffContract.ACTION_REGISTER_ACK, HandoffContract.ACTION_REGISTER_OK -> {
                connected.set(true);
                currentReconnectDelayMs = INITIAL_RECONNECT_DELAY_MS;
                LOGGER.info("Handoff control stream registered and active with backend for '{}'.", serverId);
            }

            case HandoffContract.ACTION_EVICT -> {
                handleEvictionOrder(document);
            }

            case HandoffContract.ACTION_PONG -> LOGGER.debug("Received PONG keepalive from backend on Handoff control stream.");

            default -> LOGGER.warn("Received unknown action '{}' on Handoff control stream.", action);
        }
    }

    private void handleEvictionOrder(@NotNull final RawBsonDocument document) {
        if (!document.containsKey(HandoffContract.FIELD_UUID) || !document.isString(HandoffContract.FIELD_UUID)) {
            LOGGER.warn("Received EVICT order without valid 'uuid'.");
            return;
        }

        final String uuidStr = document.getString(HandoffContract.FIELD_UUID).getValue();
        final UUID uuid;
        try {
            uuid = UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            LOGGER.error("Received EVICT order with invalid UUID format: {}", uuidStr);
            return;
        }

        LOGGER.info("Received Handoff EVICT order for player {} — freezing and extracting dirty components...", uuid);

        final TachyonProfile profile = profileRegistry.getProfile(uuid);
        final RawBsonDocument payload;

        if (profile != null) {
            profile.freeze();
            final var delta = PlayerDeltaAdapter.fromProfile(profile);
            payload = delta.toRawBsonDocument();
            profileRegistry.unloadProfile(uuid, "HANDOFF");
            LOGGER.info("Profile for {} evicted, frozen and unloaded successfully.", uuid);
        } else {
            LOGGER.info("Player {} was not loaded in RAM on '{}' — returning empty dirty payload.", uuid, serverId);
            final var emptyDoc = new BsonDocument(PlayerDataContract.FIELD_UUID, new BsonString(uuidStr))
                    .append(PlayerDataContract.FIELD_SAVE, new BsonDocument())
                    .append(PlayerDataContract.FIELD_REMOVE, new BsonArray())
                    .append(PlayerDataContract.FIELD_VERSIONS, new BsonDocument());
            payload = BsonUtil.toRawBsonDocument(emptyDoc);
        }

        final var ackDoc = new BsonDocument(HandoffContract.FIELD_ACTION, new BsonString(HandoffContract.ACTION_EVICT_ACK))
                .append(HandoffContract.FIELD_UUID, new BsonString(uuidStr))
                .append(HandoffContract.FIELD_SUCCESS, new BsonBoolean(true))
                .append(HandoffContract.FIELD_PAYLOAD, payload);

        final StreamObserver<RawBsonDocument> observer = this.requestObserver;
        if (observer != null) {
            try {
                observer.onNext(BsonUtil.toRawBsonDocument(ackDoc));
                LOGGER.info("Sent EVICT_ACK for player {} to backend.", uuid);
            } catch (Exception e) {
                LOGGER.error("Failed to send EVICT_ACK for player {}: {}", uuid, e.getMessage());
            }
        } else {
            LOGGER.error("Cannot send EVICT_ACK for player {}: stream observer is null.", uuid);
        }
    }

    private void scheduleReconnect() {
        if (!running.get()) {
            return;
        }

        final long delay = currentReconnectDelayMs;
        currentReconnectDelayMs = Math.min(currentReconnectDelayMs * 2, MAX_RECONNECT_DELAY_MS);

        LOGGER.info("Scheduling Handoff reconnect in {}ms...", delay);
        reconnectExecutor.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
    }

    public boolean isConnected() {
        return connected.get() && running.get();
    }

    public void sendKeepAlive() {
        if (!connected.get() || !running.get()) {
            return;
        }

        final StreamObserver<RawBsonDocument> observer = this.requestObserver;
        if (observer != null) {
            try {
                final var pingDoc = new BsonDocument(HandoffContract.FIELD_ACTION, new BsonString(HandoffContract.ACTION_PING));
                observer.onNext(BsonUtil.toRawBsonDocument(pingDoc));
                LOGGER.debug("Sent PING keepalive on Handoff control stream.");
            } catch (Exception e) {
                LOGGER.warn("Failed to send keepalive on Handoff stream: {}", e.getMessage());
            }
        }
    }

    public void shutdown() {
        running.set(false);
        connected.set(false);

        final StreamObserver<RawBsonDocument> observer = this.requestObserver;
        if (observer != null) {
            try {
                observer.onCompleted();
            } catch (Exception ignored) {
            }
            this.requestObserver = null;
        }

        reconnectExecutor.shutdown();
        try {
            if (!reconnectExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                reconnectExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            reconnectExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        LOGGER.info("Handoff client stream shut down cleanly.");
    }
}
