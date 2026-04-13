package tech.skworks.tachyon.snapshot;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.quarkus.grpc.GrpcService;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.ValueCommands;
import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import tech.skworks.tachyon.contracts.common.StandardResponse;
import tech.skworks.tachyon.contracts.player.PlayerResponse;
import tech.skworks.tachyon.contracts.snapshot.RevertRequest;
import tech.skworks.tachyon.contracts.snapshot.SnapshotRequest;
import tech.skworks.tachyon.contracts.snapshot.SnapshotServiceGrpc;

import java.nio.charset.StandardCharsets;

/**
 * Project Tachyon
 * Class SnapshotGrpcService
 *
 * @author  Jimmy (vSKAH) - 06/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
@GrpcService
@Blocking
public class SnapshotGrpcService extends SnapshotServiceGrpc.SnapshotServiceImplBase {

    @Inject
    Logger log;
    @Inject
    MongoClient mongo;
    @Inject
    SnapshotConfig config;

    @ConfigProperty(name = "tachyon.database.name")
    String dbName;

    private final ValueCommands<String, byte[]> redisBytes;

    private MongoCollection<Document> playersCollection;
    private MongoCollection<Document> snapshotCollection;

    public SnapshotGrpcService(RedisDataSource redisDS) {
        this.redisBytes = redisDS.value(byte[].class);
    }

    @PostConstruct
    void init() {
        this.playersCollection = mongo.getDatabase(dbName).getCollection(config.playersCollection());
        this.snapshotCollection = mongo.getDatabase(dbName).getCollection(config.snapshotsCollection());
        log.debug("SnapshotGrpcService collections initialized.");
    }

    @Override
    public void createSnapshot(SnapshotRequest req, StreamObserver<StandardResponse> obs) {
        String uuid = req.getUuid();
        String reason = req.getReason().isBlank() ? "MANUAL" : req.getReason();

        try {
            Document playerDoc = playersCollection.find(Filters.eq("uuid", uuid)).first();

            if (playerDoc == null) {
                log.infof("createSnapshot: no document found for uuid=%s, nothing to snapshot.", uuid);
                obs.onNext(StandardResponse.newBuilder().setSuccess(true).setMessage("Player not found, no snapshot created.").build());
                obs.onCompleted();
                return;
            }

            Document docToStore = Document.parse(playerDoc.toJson());
            docToStore.remove("_id");

            byte[] data = docToStore.toJson().getBytes(StandardCharsets.UTF_8);

            Document snapshot = new Document("uuid", uuid).append("timestamp", System.currentTimeMillis()).append("reason", reason).append("data", new Binary(data));

            snapshotCollection.insertOne(snapshot);

            log.infof("Snapshot created for player %s (reason: %s)", uuid, reason);
            obs.onNext(StandardResponse.newBuilder().setSuccess(true).build());
            obs.onCompleted();

        } catch (Exception e) {
            log.errorf(e, "Failed to create snapshot for %s", uuid);
            obs.onError(Status.INTERNAL.withDescription("SNAPSHOT_ERROR").withCause(e).asRuntimeException());
        }
    }

    @Override
    public void revertToSnapshot(RevertRequest req, StreamObserver<PlayerResponse> obs) {
        String uuid = req.getUuid();

        try {
            Document snapshot = snapshotCollection.find(Filters.eq("_id", new ObjectId(req.getSnapshotId()))).first();

            if (snapshot == null) {
                obs.onError(Status.NOT_FOUND.withDescription("Snapshot not found").asRuntimeException());
                return;
            }

            byte[] oldDataBytes = snapshot.get("data", org.bson.types.Binary.class).getData();
            Document restoredDoc = Document.parse(new String(oldDataBytes, StandardCharsets.UTF_8));

            playersCollection.replaceOne(Filters.eq("uuid", uuid), restoredDoc);

            redisBytes.getdel("player:cache:" + uuid);

            log.infof("Player %s reverted to snapshot %s", uuid, req.getSnapshotId());

            obs.onNext(PlayerResponse.newBuilder().setUuid(uuid).build());
            obs.onCompleted();

        } catch (Exception e) {
            log.errorf(e, "Failed to revert snapshot for %s", uuid);
            obs.onError(Status.INTERNAL.withDescription("REVERT_ERROR").withCause(e).asRuntimeException());
        }
    }
}
