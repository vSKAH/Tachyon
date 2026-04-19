package tech.skworks.tachyon.service.snapshot;

import com.github.luben.zstd.Zstd;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.protobuf.*;
import com.google.protobuf.util.JsonFormat;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import io.grpc.Status;
import io.quarkus.grpc.GrpcService;
import io.quarkus.mongodb.FindOptions;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.quarkus.mongodb.reactive.ReactiveMongoCollection;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.stream.ReactiveStreamCommands;
import io.quarkus.redis.datasource.stream.XAddArgs;
import io.smallrye.common.annotation.NonBlocking;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import tech.skworks.tachyon.service.contracts.player.GetPlayerResponse;
import tech.skworks.tachyon.service.contracts.snapshot.*;
import tech.skworks.tachyon.service.infra.DynamicProtobufRegistry;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Project Tachyon
 * Class SnapshotGrpcService
 *
 * @author  Jimmy (vSKAH) - 06/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
@GrpcService
@NonBlocking
public class SnapshotGrpcService extends MutinySnapshotServiceGrpc.SnapshotServiceImplBase {

    @Inject
    Logger log;
    @Inject
    SnapshotConfig snapshotConfig;

    @Inject
    ReactiveMongoClient mongoClient;
    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    @Inject
    DynamicProtobufRegistry protobufRegistry;

    private final ReactiveStreamCommands<String, String, byte[]> redisStream;
    private static final XAddArgs STREAM_ARGS = new XAddArgs().maxlen(20000L).nearlyExactTrimming();

    private ReactiveMongoCollection<Document> snapshotCollection;

    public SnapshotGrpcService(ReactiveRedisDataSource redisDS) {
        this.redisStream = redisDS.stream(byte[].class);
    }

    @PostConstruct
    void init() {
        this.snapshotCollection = mongoClient.getDatabase(databaseName).getCollection(snapshotConfig.collection());
        this.log.debug("SnapshotGrpcService collections initialized.");
    }

   @Override
    public Uni<Empty> takeDatabaseSnapshot(TakeDatabaseSnapshotRequest req) {
        Map<String, byte[]> payload = new HashMap<>();
        payload.put("granularity", "FULL".getBytes(StandardCharsets.UTF_8));
        payload.put("global_payload", req.toByteArray());

        return pushToStream(payload);
    }

    @Override
    public Uni<Empty> takeComponentSnapshot(TakeComponentSnapshotRequest req) {
        if (req.getRawData().isEmpty()) {
            return Uni.createFrom().failure(Status.INVALID_ARGUMENT.withDescription("Raw data is required for specific snapshots.").asRuntimeException());
        }

        Map<String, byte[]> payload = new HashMap<>();
        payload.put("granularity", "SPECIFIC_COMPONENT".getBytes(StandardCharsets.UTF_8));
        payload.put("specific_payload", req.toByteArray());
        return pushToStream(payload);
    }

    private Uni<Empty> pushToStream(Map<String, byte[]> payload) {
        payload.put("source", "EXTERNAL".getBytes(StandardCharsets.UTF_8));
        payload.put("timestamp", String.valueOf(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));

        return redisStream.xadd(snapshotConfig.streamKey(), STREAM_ARGS, payload)
                .replaceWith(Empty.getDefaultInstance())
                .onFailure().invoke(e -> log.error("Redis Stream Error", e))
                .onFailure().transform(e -> Status.UNAVAILABLE.withDescription("The snapshot buffer (Redis) is currently unavailable.").withCause(e).asRuntimeException());
    }

    @Override
    public Uni<ListSnapshotsResponse> listSnapshots(ListSnapshotsRequest req) {
        if (req.getPlayerId().isBlank()) {
            return Uni.createFrom().failure(Status.INVALID_ARGUMENT.withDescription("UUID is required").asRuntimeException());
        }

        return snapshotCollection.find(Filters.eq("uuid", req.getPlayerId()), new FindOptions()
                        .sort(Sorts.descending("timestamp"))
                        .projection(Projections.exclude("data", "uuid")))
                .collect().asList().onFailure().transform(e -> {
                    log.errorf(e, "Failed to fetch snapshots for player %s", req.getPlayerId());
                    return Status.INTERNAL.withDescription("Database error occurred").withCause(e).asRuntimeException();
                }).map(docs -> {
                    ListSnapshotsResponse.Builder res = ListSnapshotsResponse.newBuilder().setPlayerId(req.getPlayerId());

                    for (Document d : docs) {
                        try {
                            String typeStr = d.getString("type");
                            SnapshotTriggerType type = (typeStr != null) ? SnapshotTriggerType.valueOf(typeStr) : SnapshotTriggerType.SNAPSHOT_TRIGGER_UNSPECIFIED;

                            res.addSnapshots(SnapshotInfo.newBuilder()
                                    .setSnapshotId(d.getObjectId("_id").toHexString())
                                    .setTriggerType(type)
                                    .setTimestamp(d.getLong("timestamp") != null ? d.getLong("timestamp") : 0L)
                                    .setReason(d.getString("reason") != null ? d.getString("reason") : "N/A")
                                    .setSource(d.getString("source") != null ? d.getString("source") : "UNKNOWN")
                                    .setGranularity(d.getString("granularity") != null ? d.getString("granularity") : "FULL")
                                    .build());
                        } catch (Exception e) {
                            log.warnf("Skipping corrupted snapshot document %s: %s", d.getObjectId("_id"), e.getMessage());
                        }
                    }

                    return res.build();
                });
    }


    @Override
    public Uni<DecodeSnapshotResponse> decodeSnapshot(DecodeSnapshotRequest req) {
        final String snapshotId = req.getSnapshotId();

        return parseObjectId(snapshotId)
                .chain(objectId -> fetchSnapshotDocument(objectId, snapshotId))
                .chain(doc -> extractAndDecompressPayload(doc)
                        .chain(decompressedBytes -> buildResponse(doc, snapshotId, decompressedBytes)))
                .onFailure().invoke(e -> log.errorf("Failed to process ViewSnapshot request for ID: %s. Reason: %s", snapshotId, e.getMessage()));
    }

    private Uni<ObjectId> parseObjectId(String snapshotId) {
        try {
            return Uni.createFrom().item(new ObjectId(snapshotId));
        } catch (IllegalArgumentException e) {
            log.errorf(e, "Invalid format for snapshot ID '%s'", snapshotId);
            return Uni.createFrom().failure(Status.INVALID_ARGUMENT.withCause(e).withDescription("The provided Snapshot ID has an invalid format.").asRuntimeException());
        }
    }

    private Uni<Document> fetchSnapshotDocument(ObjectId objectId, String snapshotId) {
        return snapshotCollection.find(Filters.eq("_id", objectId)).collect().first()
                .onItem().ifNull().failWith(() -> Status.NOT_FOUND.withDescription("This snapshot does not exist in the database.").asRuntimeException())
                .onFailure(e -> !(e instanceof io.grpc.StatusRuntimeException))
                .transform(e -> {
                    log.errorf(e, "Database error while fetching snapshot %s", snapshotId);
                    return Status.INTERNAL.withDescription("Database error occurred").withCause(e).asRuntimeException();
                });
    }

    private Uni<byte[]> extractAndDecompressPayload(Document doc) {
        final Binary binary = doc.get("data", Binary.class);
        if (binary == null) {
            return Uni.createFrom().failure(Status.DATA_LOSS.withDescription("Snapshot document is missing the 'data' field.").asRuntimeException());
        }
        return Uni.createFrom().item(() -> decompressPayload(binary.getData())).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    private Uni<DecodeSnapshotResponse> buildResponse(Document doc, String snapshotId, byte[] decompressedBytes) {
        final String granularity = doc.getString("granularity");

        if (granularity == null) {
            return Uni.createFrom().failure(Status.DATA_LOSS.withDescription("Snapshot document is missing the 'granularity' field.").asRuntimeException());
        }

        try {
            if (granularity.equalsIgnoreCase("FULL")) {
                return processFullSnapshot(snapshotId, decompressedBytes);
            } else if (granularity.equalsIgnoreCase("SPECIFIC_COMPONENT")) {
                return processSpecificSnapshot(doc, snapshotId, decompressedBytes);
            } else {
                return Uni.createFrom().failure(Status.INVALID_ARGUMENT.withDescription("Unknown granularity: " + granularity).asRuntimeException());
            }
        } catch (Exception e) {
            log.errorf(e, "Data parsing corrupted for snapshot (ID: %s, Granularity: %s)", snapshotId, granularity);
            return Uni.createFrom().failure(Status.DATA_LOSS.withDescription("Snapshot data is corrupted or cannot be parsed.").asRuntimeException());
        }
    }

    private Uni<DecodeSnapshotResponse> processSpecificSnapshot(Document doc, String snapshotId, byte[] decompressedBytes) {
        final String targetComponent = doc.getString("target_component");
        if (targetComponent == null) {
            return Uni.createFrom().failure(Status.DATA_LOSS.withDescription("Specific Snapshot is missing the 'target_component' field in database.").asRuntimeException());
        }
        final Any componentAny = Any.newBuilder().setTypeUrl("type.googleapis.com/" + targetComponent).setValue(ByteString.copyFrom(decompressedBytes)).build();
        final DecodeSnapshotResponse response = DecodeSnapshotResponse.newBuilder().setSnapshotId(snapshotId).putComponents(targetComponent, componentAny).build();
        return Uni.createFrom().item(response);
    }


    private Uni<DecodeSnapshotResponse> processFullSnapshot(String snapshotId, byte[] decompressedBytes) {
        final DecodeSnapshotResponse.Builder response = DecodeSnapshotResponse.newBuilder().setSnapshotId(snapshotId);

        final String jsonPayload = new String(decompressedBytes, StandardCharsets.UTF_8);
        final JsonObject document = JsonParser.parseString(jsonPayload).getAsJsonObject();

        if (!document.has("components")) {
            return Uni.createFrom().failure(Status.DATA_LOSS.withDescription("Unable to find 'components' section inside the FULL snapshot: " + snapshotId).asRuntimeException());
        }

        JsonObject componentsJson = document.getAsJsonObject("components");
        for (Map.Entry<String, JsonElement> entry : componentsJson.entrySet()) {
            JsonObject componentData = entry.getValue().getAsJsonObject();

            if (!componentData.has("@type")) {
                log.warnf("Missing '@type' in 'components.%s' of snapshot: %s", entry.getKey(), snapshotId);
                continue;
            }

            final String typeName = componentData.get("@type").getAsString();
            final Any component = buildComponent(snapshotId, typeName, componentData);
            response.putComponents(typeName, component);
        }

        return Uni.createFrom().item(response.build());
    }

    private Any buildComponent(final String snapshotId, final String typeName, final JsonObject datas) {
        Descriptors.Descriptor descriptor = protobufRegistry.findDescriptor(typeName);
        if (descriptor == null) {
            throw Status.DATA_LOSS.withDescription("Descriptor not found for type " + typeName + " (Snapshot ID: " + snapshotId + ")").asRuntimeException();
        }
        final DynamicMessage.Builder dynamicBuilder = DynamicMessage.newBuilder(descriptor);
        try {
            JsonFormat.parser().ignoringUnknownFields().merge(datas.toString(), dynamicBuilder);
            return Any.pack(dynamicBuilder.build());
        } catch (InvalidProtocolBufferException e) {
            throw Status.INTERNAL.withDescription("Failed to build component for type " + typeName + " (Snapshot ID: " + snapshotId + ")").asRuntimeException();
        }
    }

    private byte[] decompressPayload(byte[] data) {
        try {
            long expectedSize = Zstd.getFrameContentSize(data, 0, data.length, false);

            if (expectedSize < 0) {
                throw Status.INTERNAL.withDescription("Zstd metadata error: Invalid frame header.").asRuntimeException();
            }
            if (expectedSize == 0) {
                throw Status.DATA_LOSS.withDescription("Zstd metadata error: Original size is unknown.").asRuntimeException();
            }

            return Zstd.decompress(data, (int) expectedSize);

        } catch (Exception e) {
            if (e instanceof RuntimeException && Status.fromThrowable(e).getCode() != Status.Code.UNKNOWN) {
                throw (RuntimeException) e;
            }
            log.error("Error decompressing snapshot data", e);
            throw Status.INTERNAL.withDescription("Failed to decompress snapshot: " + e.getMessage()).asRuntimeException();
        }
    }

    @Override
    public Uni<GetPlayerResponse> revertToSnapshot(RevertToSnapshotRequest request) {
        return super.revertToSnapshot(request);
    }
}
