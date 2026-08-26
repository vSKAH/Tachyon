package tech.skworks.tachyon.service.snapshot;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import io.grpc.*;
import io.grpc.stub.ServerCalls;
import io.quarkus.grpc.GrpcService;
import io.quarkus.mongodb.FindOptions;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.quarkus.mongodb.reactive.ReactiveMongoCollection;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.stream.ReactiveStreamCommands;
import io.quarkus.redis.datasource.stream.XAddArgs;
import io.smallrye.common.annotation.NonBlocking;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import org.bson.*;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import tech.skworks.tachyon.common.contract.SnapshotContract;
import tech.skworks.tachyon.common.marshaller.BsonMarshaller;
import tech.skworks.tachyon.service.audit.AuditGrpcService;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Project Tachyon
 * Class SnapshotGrpcService
 * Pure BSON Snapshot gRPC Service
 *
 * @author  Jimmy (vSKAH) - 06/04/2026
 * @version 2.0
 * @since 1.0.0-SNAPSHOT
 */
@GrpcService
@NonBlocking
public class SnapshotGrpcService implements BindableService {

    @Inject
    Logger logger;
    @Inject
    SnapshotConfig snapshotConfig;

    @Inject
    ReactiveMongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database", defaultValue = "tachyon")
    String databaseName;

    private ReactiveMongoCollection<Document> snapshotCollection;

    private final ReactiveStreamCommands<String, String, byte[]> redisStream;
    private static final XAddArgs STREAM_ARGS = new XAddArgs().maxlen(50000L).nearlyExactTrimming();

    public SnapshotGrpcService(ReactiveRedisDataSource redisDS) {
        this.redisStream = redisDS.stream(byte[].class);
    }

    @PostConstruct
    void init() {
        this.snapshotCollection = mongoClient.getDatabase(databaseName).getCollection(snapshotConfig.collection());
        this.logger.debug("SnapshotGrpcService collections initialized.");
    }

    @Override
    public ServerServiceDefinition bindService() {
        return ServerServiceDefinition.builder(SnapshotContract.SERVICE_NAME)
                .addMethod(SnapshotContract.TAKE_DATABASE_SNAPSHOT, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
                    takeDatabaseSnapshot(request).subscribe().with(
                            response -> {
                                responseObserver.onNext(response);
                                responseObserver.onCompleted();
                            },
                            responseObserver::onError
                    );
                }))
                .addMethod(SnapshotContract.TAKE_COMPONENT_SNAPSHOT, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
                    takeComponentSnapshot(request).subscribe().with(
                            response -> {
                                responseObserver.onNext(response);
                                responseObserver.onCompleted();
                            },
                            responseObserver::onError
                    );
                }))
                .addMethod(SnapshotContract.TOGGLE_SNAPSHOT_LOCK, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
                    toggleLockSnapshot(request).subscribe().with(
                            response -> {
                                responseObserver.onNext(response);
                                responseObserver.onCompleted();
                            },
                            responseObserver::onError
                    );
                }))
                .addMethod(SnapshotContract.LIST_SNAPSHOT, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
                    listSnapshots(request).subscribe().with(
                            response -> {
                                responseObserver.onNext(response);
                                responseObserver.onCompleted();
                            },
                            responseObserver::onError
                    );
                }))
                .addMethod(SnapshotContract.DECODE_SNAPSHOT, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
                    decodeSnapshot(request).subscribe().with(
                            response -> {
                                responseObserver.onNext(response);
                                responseObserver.onCompleted();
                            },
                            responseObserver::onError
                    );
                }))
                .build();
    }

    private Uni<ObjectId> parseObjectId(String snapshotId) {
        try {
            return Uni.createFrom().item(new ObjectId(snapshotId));
        } catch (IllegalArgumentException e) {
            logger.errorf(e, "Invalid format for snapshot ID '%s'", snapshotId);
            return Uni.createFrom().failure(Status.INVALID_ARGUMENT.withCause(e).withDescription("The provided Snapshot ID has an invalid format.").asRuntimeException());
        }
    }

    public Uni<RawBsonDocument> toggleLockSnapshot(RawBsonDocument request) {
        if (!request.containsKey("snapshot_id")) {
            return Uni.createFrom().failure(Status.INVALID_ARGUMENT.withDescription("snapshot_id is required").asRuntimeException());
        }
        final String snapshotId = request.getString("snapshot_id").getValue();
        final String lockerId = request.containsKey("locker_id") ? request.getString("locker_id").getValue() : "SYSTEM";

        return parseObjectId(snapshotId)
                .chain(objectId -> {
                    Bson filter = Filters.eq("_id", objectId);

                    return snapshotCollection.find(filter).collect().first()
                            .onItem().ifNull().failWith(() -> new StatusRuntimeException(Status.NOT_FOUND.withDescription("This snapshot does not exist in the database.")))
                            .chain(document -> {
                                boolean currentLock = document.getBoolean("locked", false);
                                boolean newLockStatus = !currentLock;

                                Bson updates = Updates.combine(
                                        Updates.set("locked", newLockStatus),
                                        newLockStatus ? Updates.set("locked_by", lockerId) : Updates.unset("locked_by")
                                );

                                return snapshotCollection.updateOne(filter, updates)
                                        .chain(result -> {
                                            if (result.getModifiedCount() == 0) {
                                                return Uni.createFrom().failure(new StatusRuntimeException(Status.ABORTED.withDescription("The snapshot was not modified.")));
                                            }
                                            BsonDocument respDoc = new BsonDocument()
                                                    .append("snapshot_id", new BsonString(snapshotId))
                                                    .append("locked", new BsonBoolean(newLockStatus));
                                            return Uni.createFrom().item(BsonMarshaller.toRawBsonDocument(respDoc));
                                        })
                                        .onFailure().invoke(e -> {
                                            if (!(e instanceof StatusRuntimeException)) {
                                                logger.error("Database error occurred during snapshot locking", e);
                                            }
                                        })
                                        .onFailure().transform(e -> {
                                            if (e instanceof StatusRuntimeException) return e;
                                            return Status.INTERNAL.withCause(e).withDescription("Database error occurred").asRuntimeException();
                                        });
                            });
                });
    }

    public Uni<RawBsonDocument> takeDatabaseSnapshot(RawBsonDocument req) {
        if (!req.containsKey("uuid")) {
            return Uni.createFrom().failure(Status.INVALID_ARGUMENT.withDescription("uuid is required").asRuntimeException());
        }
        Map<String, byte[]> payload = new HashMap<>();
        payload.put("granularity", "FULL".getBytes(StandardCharsets.UTF_8));
        payload.put("global_payload", toByteArray(req));

        return pushToStream(payload);
    }

    public Uni<RawBsonDocument> takeComponentSnapshot(RawBsonDocument req) {
        if (!req.containsKey("uuid") || !req.containsKey("target_component")) {
            return Uni.createFrom().failure(Status.INVALID_ARGUMENT.withDescription("uuid and target_component are required for specific snapshots.").asRuntimeException());
        }

        Map<String, byte[]> payload = new HashMap<>();
        payload.put("granularity", "SPECIFIC_COMPONENT".getBytes(StandardCharsets.UTF_8));
        payload.put("specific_payload", toByteArray(req));
        return pushToStream(payload);
    }

    private Uni<RawBsonDocument> pushToStream(Map<String, byte[]> payload) {
        payload.put("source", "EXTERNAL".getBytes(StandardCharsets.UTF_8));
        payload.put("timestamp", String.valueOf(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));

        return redisStream.xadd(snapshotConfig.streamKey(), STREAM_ARGS, payload)
                .replaceWith(BsonMarshaller.EMPTY)
                .onFailure().invoke(e -> logger.error("Redis Stream Error", e))
                .onFailure().transform(e -> Status.UNAVAILABLE.withDescription("The snapshot buffer (Redis) is currently unavailable.").withCause(e).asRuntimeException());
    }

    public Uni<RawBsonDocument> listSnapshots(RawBsonDocument req) {
        if (!req.containsKey("uuid")) {
            return Uni.createFrom().failure(Status.INVALID_ARGUMENT.withDescription("uuid is required").asRuntimeException());
        }
        final String uuid = req.getString("uuid").getValue();

        return snapshotCollection.find(Filters.eq("uuid", uuid), new FindOptions()
                        .sort(Sorts.descending("timestamp"))
                        .projection(Projections.exclude("components", "data")))
                .collect().asList().onFailure().transform(e -> {
                    logger.errorf(e, "Failed to fetch snapshots for player %s", uuid);
                    return Status.INTERNAL.withDescription("Database error occurred").withCause(e).asRuntimeException();
                }).map(docs -> {
                    BsonArray snapshotsArray = new BsonArray();

                    for (Document document : docs) {
                        try {
                            String typeStr = document.getString("trigger_type");
                            Number timestampNum = document.get("timestamp", Number.class);
                            long timestamp = (timestampNum != null) ? timestampNum.longValue() : 0L;

                            String reason = document.getString("reason");
                            String source = document.getString("source");
                            String granularity = document.getString("granularity");

                            BsonDocument snapDoc = new BsonDocument()
                                    .append("snapshot_id", new BsonString(document.getObjectId("_id").toHexString()))
                                    .append("trigger_type", new BsonString(typeStr != null ? typeStr : "UNKNOWN"))
                                    .append("timestamp", new BsonInt64(timestamp))
                                    .append("reason", new BsonString(reason != null ? reason : "N/A"))
                                    .append("source", new BsonString(source != null ? source : "UNKNOWN"))
                                    .append("granularity", new BsonString(granularity != null ? granularity : "FULL"))
                                    .append("locked", new BsonBoolean(document.getBoolean("locked", false)));

                            snapshotsArray.add(snapDoc);

                        } catch (Exception e) {
                            logger.warnf("Skipping corrupted snapshot document %s: %s", document.getObjectId("_id"), e.getMessage());
                        }
                    }

                    BsonDocument responseDoc = new BsonDocument()
                            .append("uuid", new BsonString(uuid))
                            .append("snapshots", snapshotsArray);

                    return BsonMarshaller.toRawBsonDocument(responseDoc);
                });
    }

    public Uni<RawBsonDocument> decodeSnapshot(RawBsonDocument req) {
        if (!req.containsKey("snapshot_id")) {
            return Uni.createFrom().failure(Status.INVALID_ARGUMENT.withDescription("snapshot_id is required").asRuntimeException());
        }
        final String snapshotId = req.getString("snapshot_id").getValue();

        return parseObjectId(snapshotId)
                .chain(objectId -> snapshotCollection.find(Filters.eq("_id", objectId)).collect().first()
                        .onItem().ifNull().failWith(() -> Status.NOT_FOUND.withDescription("This snapshot does not exist in the database.").asRuntimeException())
                        .onFailure(e -> !(e instanceof StatusRuntimeException))
                        .transform(e -> {
                            logger.errorf(e, "Database error while fetching snapshot %s", snapshotId);
                            return Status.INTERNAL.withDescription("Database error occurred").withCause(e).asRuntimeException();
                        }))
                .map(doc -> {
                    String granularity = doc.getString("granularity");
                    String uuid = doc.getString("uuid");
                    Number timestampNum = doc.get("timestamp", Number.class);
                    long timestamp = timestampNum != null ? timestampNum.longValue() : 0L;

                    BsonDocument response = new BsonDocument()
                            .append("snapshot_id", new BsonString(snapshotId))
                            .append("uuid", new BsonString(uuid != null ? uuid : ""))
                            .append("granularity", new BsonString(granularity != null ? granularity : "FULL"))
                            .append("timestamp", new BsonInt64(timestamp));

                    if (doc.containsKey("components")) {
                        Document componentsDoc = doc.get("components", Document.class);
                        if (componentsDoc != null) {
                            response.append("components", componentsDoc.toBsonDocument());
                        } else {
                            response.append("components", new BsonDocument());
                        }
                    } else {
                        response.append("components", new BsonDocument());
                    }

                    return BsonMarshaller.toRawBsonDocument(response);
                })
                .onFailure().invoke(e -> logger.errorf("Failed to process ViewSnapshot request for ID: %s. Reason: %s", snapshotId, e.getMessage()));
    }

    private static byte[] toByteArray(RawBsonDocument doc) {
        return AuditGrpcService.toByteArray(doc);
    }

}
