package tech.skworks.tachyon.service.snapshot;

import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
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
import io.smallrye.common.constraint.NotNull;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import org.bson.*;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import tech.skworks.tachyon.common.BsonUtil;
import tech.skworks.tachyon.common.contract.SnapshotContract;
import tech.skworks.tachyon.common.types.Empty;
import tech.skworks.tachyon.service.audit.AuditGrpcService;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    private ReactiveMongoCollection<RawBsonDocument> snapshotCollection;

    private final ReactiveStreamCommands<String, String, byte[]> redisStream;
    private static final XAddArgs STREAM_ARGS = new XAddArgs().maxlen(50000L).nearlyExactTrimming();

    public SnapshotGrpcService(ReactiveRedisDataSource redisDS) {
        this.redisStream = redisDS.stream(byte[].class);
    }

    @PostConstruct
    void init() {
        this.snapshotCollection = mongoClient.getDatabase(databaseName).getCollection(snapshotConfig.collection(), RawBsonDocument.class);
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
                .addMethod(SnapshotContract.LIST_SNAPSHOT_DAYS, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
                    listSnapshotDays(request).subscribe().with(
                            response -> {
                                responseObserver.onNext(response);
                                responseObserver.onCompleted();
                            },
                            responseObserver::onError
                    );
                }))
                .addMethod(SnapshotContract.GET_SNAPSHOTS_FOR_DAY, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
                    getSnapshotsForDay(request).subscribe().with(
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

    public Uni<RawBsonDocument> toggleLockSnapshot(@NotNull final RawBsonDocument request) {
        Objects.requireNonNull(request, "request cannot be null");

        if (!request.containsKey("snapshot_id")) {
            return Uni.createFrom().failure(Status.INVALID_ARGUMENT.withDescription("snapshot_id is required").asRuntimeException());
        }

        final String snapshotId = request.getString("snapshot_id").getValue();
        final String lockerId = request.containsKey("locker_id") ? request.getString("locker_id").getValue() : "SYSTEM";

        return parseObjectId(snapshotId)
                .chain(objectId -> {
                    final var filter = Filters.eq("_id", objectId);

                    return snapshotCollection.find(filter).collect().first()
                            .onItem().ifNull().failWith(() -> new StatusRuntimeException(Status.NOT_FOUND.withDescription("This snapshot does not exist in the database.")))
                            .chain(document -> {
                                boolean currentLock = document.getBoolean("locked", new BsonBoolean(false)).getValue();
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
                                            return Uni.createFrom().item(BsonUtil.toRawBsonDocument(respDoc));
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

    public Uni<Empty> takeDatabaseSnapshot(RawBsonDocument req) {
        if (!req.containsKey("uuid")) {
            return Uni.createFrom().failure(Status.INVALID_ARGUMENT.withDescription("uuid is required").asRuntimeException());
        }
        Map<String, byte[]> payload = new HashMap<>();
        payload.put("granularity", "FULL".getBytes(StandardCharsets.UTF_8));
        payload.put("global_payload", BsonUtil.toByteArray(req));

        return pushToStream(payload);
    }

    public Uni<Empty> takeComponentSnapshot(RawBsonDocument req) {
        if (!req.containsKey("uuid") || !req.containsKey("target_component")) {
            return Uni.createFrom().failure(Status.INVALID_ARGUMENT.withDescription("uuid and target_component are required for specific snapshots.").asRuntimeException());
        }

        Map<String, byte[]> payload = new HashMap<>();
        payload.put("granularity", "SPECIFIC_COMPONENT".getBytes(StandardCharsets.UTF_8));
        payload.put("specific_payload", BsonUtil.toByteArray(req));
        return pushToStream(payload);
    }

    private Uni<Empty> pushToStream(Map<String, byte[]> payload) {
        payload.put("source", "EXTERNAL".getBytes(StandardCharsets.UTF_8));
        payload.put("timestamp", String.valueOf(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));

        return redisStream.xadd(snapshotConfig.streamKey(), STREAM_ARGS, payload)
                .replaceWith(Empty.INSTANCE)
                .onFailure().invoke(e -> logger.error("Redis Stream Error", e))
                .onFailure().transform(e -> Status.UNAVAILABLE.withDescription("The snapshot buffer (Redis) is currently unavailable.").withCause(e).asRuntimeException());
    }

    public Uni<RawBsonDocument> listSnapshotDays(@NotNull final RawBsonDocument req) {
        Objects.requireNonNull(req, "req cannot be null");
        if (!req.containsKey("uuid") || !req.isString("uuid")) {
            return Uni.createFrom().failure(Status.INVALID_ARGUMENT.withDescription("uuid is required").asRuntimeException());
        }
        final String uuid = req.getString("uuid").getValue();

        final List<Bson> pipeline = List.of(
                Aggregates.match(Filters.eq("uuid", uuid)),
                Aggregates.project(Projections.fields(
                        Projections.computed("date", new Document("$dateToString", new Document("format", "%Y-%m-%d").append("date", new Document("$toDate", "$timestamp")))),
                        Projections.include("timestamp")
                )),
                Aggregates.group("$date",
                        Accumulators.sum("count", 1),
                        Accumulators.max("last_timestamp", "$timestamp")
                ),
                Aggregates.sort(Sorts.descending("_id"))
        );

        return snapshotCollection.aggregate(pipeline).collect().asList()
                .onFailure().transform(e -> {
                    logger.errorf(e, "Failed to aggregate snapshot days for player %s", uuid);
                    return Status.INTERNAL.withDescription("Database error occurred while fetching snapshot days").withCause(e).asRuntimeException();
                })
                .map(docs -> {
                    final var daysArray = new BsonArray();
                    for (final var doc : docs) {
                        final var dateStr = doc.getString("_id");
                        final var count = doc.getNumber("count", new BsonInt32(0));
                        final var lastTimestamp = doc.getNumber("last_timestamp", new BsonInt64(0L));

                        if (dateStr == null) continue;

                        final var dayDocument = new BsonDocument().append("date", dateStr).append("count", count).append("last_timestamp", lastTimestamp);
                        daysArray.add(dayDocument);
                    }
                    return BsonUtil.toRawBsonDocument(new BsonDocument("days", daysArray));
                });
    }

    public Uni<RawBsonDocument> getSnapshotsForDay(@NotNull final RawBsonDocument req) {
        Objects.requireNonNull(req, "req cannot be null");
        if (!req.containsKey("uuid") || !req.isString("uuid")) {
            return Uni.createFrom().failure(Status.INVALID_ARGUMENT.withDescription("uuid is required").asRuntimeException());
        }
        final String uuid = req.getString("uuid").getValue();

        final Bson filter;
        if (req.containsKey("start_timestamp") && req.containsKey("end_timestamp")) {
            final var startTimestamp = req.getInt64("start_timestamp").getValue();
            final var endTimestamp = req.getInt64("end_timestamp").getValue();
            filter = Filters.and(
                    Filters.eq("uuid", uuid),
                    Filters.gte("timestamp", startTimestamp),
                    Filters.lte("timestamp", endTimestamp)
            );
        } else if (req.containsKey("date") && req.isString("date")) {
            final var date = req.getString("date").getValue();
            final var localDate = LocalDate.parse(date);
            final var startTimestamp = localDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            final var endTs = localDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 1;
            filter = Filters.and(
                    Filters.eq("uuid", uuid),
                    Filters.gte("timestamp", startTimestamp),
                    Filters.lte("timestamp", endTs)
            );
        } else {
            return Uni.createFrom().failure(Status.INVALID_ARGUMENT.withDescription("start_timestamp/end_timestamp or date is required").asRuntimeException());
        }

        return snapshotCollection.find(filter, new FindOptions().sort(Sorts.descending("timestamp"))
                        .projection(Projections.exclude("components", "data")))
                .collect().asList()
                .onFailure().transform(e -> {
                    logger.errorf(e, "Failed to fetch snapshots for player %s for day", uuid);
                    return Status.INTERNAL.withDescription("Database error occurred").withCause(e).asRuntimeException();
                })
                .map(docs -> {
                    final var snapshotsArray = new BsonArray();
                    for (final var document : docs) {
                        try {
                            final var triggerType = document.getString("trigger_type", new BsonString("UNKNOWN"));
                            final var timestamp = document.get("timestamp", new BsonInt64(0L));

                            final var reason = document.getString("reason", new BsonString("N/A"));
                            final var source = document.getString("source", new BsonString("UNKNOWN"));
                            final var granularity = document.getString("granularity", new BsonString("FULL"));

                            final var snapDoc = new BsonDocument()
                                    .append("snapshot_id", new BsonString(document.getObjectId("_id").getValue().toHexString()))
                                    .append("trigger_type", triggerType)
                                    .append("timestamp", timestamp)
                                    .append("reason", reason)
                                    .append("source", source)
                                    .append("granularity", granularity)
                                    .append("locked", document.getBoolean("locked", new BsonBoolean(false)));

                            snapshotsArray.add(snapDoc);
                        } catch (Exception e) {
                            logger.warnf("Skipping corrupted snapshot document %s: %s", document.getObjectId("_id").getValue(), e.getMessage());
                        }
                    }
                    return BsonUtil.toRawBsonDocument(new BsonDocument("snapshots", snapshotsArray));
                });
    }

    public Uni<RawBsonDocument> decodeSnapshot(final @NotNull RawBsonDocument req) {
        if (!req.containsKey("snapshot_id")) {
            return Uni.createFrom().failure(Status.INVALID_ARGUMENT.withDescription("snapshot_id is required").asRuntimeException());
        }
        final var snapshotId = req.getString("snapshot_id");

        final var projection = new FindOptions().projection(Projections.fields(
                Projections.include("components", "target_component", "data"),
                Projections.excludeId()
        ));

        return parseObjectId(snapshotId.getValue())
                .chain(objectId -> snapshotCollection.find(Filters.eq("_id", objectId), projection).collect().first()
                        .onItem().ifNull().failWith(() -> Status.NOT_FOUND.withDescription("This snapshot does not exist in the database.").asRuntimeException())
                        .onFailure(e -> !(e instanceof StatusRuntimeException))
                        .transform(e -> {
                            logger.errorf(e, "Database error while fetching snapshot %s", snapshotId.getValue());
                            return Status.INTERNAL.withDescription("Database error occurred").withCause(e).asRuntimeException();
                        }))
                .map(doc -> {
                    BsonDocument componentsBson;
                    if (doc.containsKey("components") && doc.isDocument("components")) {
                        componentsBson = doc.getDocument("components");
                    } else if (doc.containsKey("target_component") && doc.containsKey("data") && doc.isDocument("data")) {
                        componentsBson = new BsonDocument(doc.getString("target_component").getValue(), doc.getDocument("data"));
                    } else {
                        componentsBson = new BsonDocument();
                    }

                    return BsonUtil.toRawBsonDocument(new BsonDocument("components", componentsBson));
                })
                .onFailure().invoke(e -> logger.errorf("Failed to process decodeSnapshot request for ID: %s. Reason: %s", snapshotId.getValue(), e.getMessage()));
    }

}
