package tech.skworks.tachyon.service.player.handoff;

import io.grpc.stub.StreamObserver;
import io.smallrye.common.constraint.NotNull;
import jakarta.inject.Inject;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.RawBsonDocument;
import org.jboss.logging.Logger;
import tech.skworks.tachyon.common.BsonUtil;
import tech.skworks.tachyon.common.contract.HandoffContract;

import java.util.concurrent.CompletableFuture;

/**
 * Short class explaination
 * <p>
 * Long class explaination with @link if needed
 * </p>
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @author Jimmy (vSKAH) - 29/09/2026
 * @version 1.0
 * @since 2.0.0-alpha.2
 */
public class HandoffSessionController implements StreamObserver<RawBsonDocument> {

    private final HandoffGrpcService handoffGrpcService;
    private final StreamObserver<RawBsonDocument> responseObserver;
    private String serverId;

    @Inject
    Logger log;

    public HandoffSessionController(@NotNull final HandoffGrpcService handoffGrpcService,
                                    @NotNull final StreamObserver<RawBsonDocument> responseObserver) {
        this.handoffGrpcService = handoffGrpcService;
        this.responseObserver = responseObserver;
    }

    @Override
    public void onNext(RawBsonDocument document) {

        if (document == null || !document.containsKey(HandoffContract.FIELD_ACTION) || !document.isString(HandoffContract.FIELD_ACTION)) {
            return;
        }

        final String action = document.getString(HandoffContract.FIELD_ACTION).getValue();
        switch (action) {
            case HandoffContract.ACTION_REGISTER -> {
                handoffGrpcService.onServerConnected(serverId, responseObserver);
                this.serverId = document.getString(HandoffContract.FIELD_SERVER_ID).getValue();
                final var ack = new BsonDocument(HandoffContract.FIELD_ACTION,
                        new BsonString(HandoffContract.ACTION_REGISTER_ACK))
                        .append(HandoffContract.FIELD_SERVER_ID, new BsonString(serverId));
                responseObserver.onNext(BsonUtil.toRawBsonDocument(ack));
            }

            case HandoffContract.ACTION_EVICT_ACK -> {
                handoffGrpcService.onEvictionAck(document);
            }

            case HandoffContract.ACTION_PING -> {
                final var pong = new BsonDocument(HandoffContract.FIELD_ACTION, new BsonString(HandoffContract.ACTION_PONG));
                responseObserver.onNext(BsonUtil.toRawBsonDocument(pong));
            }

            default -> log.warnf("[HandoffGrpcService] Unrecognized action '%s' on control stream.", action);

        }

    }


    @Override
    public void onError(Throwable t) {
        log.warnf("[HandoffSessionController] Control stream error for server '%s': %s", serverId, t.getMessage());
        cleanup();
    }

    @Override
    public void onCompleted() {
        cleanup();
        log.infof("[HandoffSessionController] Control stream closed cleanly for server '%s'.", serverId);
        responseObserver.onCompleted();
    }


    private void cleanup() {
        if (serverId != null)
            handoffGrpcService.onServerDisconnected(serverId, responseObserver);
    }
}
