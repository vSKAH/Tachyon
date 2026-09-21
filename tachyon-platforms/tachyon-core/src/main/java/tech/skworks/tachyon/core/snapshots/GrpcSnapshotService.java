package tech.skworks.tachyon.core.snapshots;

import io.grpc.CallOptions;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCalls;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.skworks.tachyon.api.component.ComponentCodec;
import tech.skworks.tachyon.api.component.ComponentNamespace;
import tech.skworks.tachyon.api.component.ComponentRegistry;
import tech.skworks.tachyon.api.snapshot.SnapshotDayInfo;
import tech.skworks.tachyon.api.snapshot.SnapshotInfo;
import tech.skworks.tachyon.api.snapshot.SnapshotService;
import tech.skworks.tachyon.api.snapshot.SnapshotTriggerType;
import tech.skworks.tachyon.common.BsonUtil;
import tech.skworks.tachyon.common.contract.SnapshotContract;
import tech.skworks.tachyon.core.grpc.AbstractGrpcService;
import tech.skworks.tachyon.core.grpc.GrpcClient;
import tech.skworks.tachyon.core.metric.scraper.TachyonCollector;
import tech.skworks.tachyon.core.resilience.ResilienceService;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Project Tachyon
 * Class GrpcSnapshotService
 *
 * @author  Jimmy (vSKAH) - 17/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class GrpcSnapshotService extends AbstractGrpcService implements SnapshotService {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tachyon/SnapshotService");
    private final ExecutorService executor;
    private final ComponentRegistry componentRegistry;

    public GrpcSnapshotService(
            @NotNull final String serverId,
            @NotNull final ResilienceService resilience,
            @NotNull final GrpcClient grpcClient,
            @NotNull final ComponentRegistry componentRegistry,
            @Nullable final TachyonCollector tachyonCollector) {
        super(serverId, "snapshot", resilience, grpcClient, tachyonCollector);
        this.executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("tachyon-snapshot-executor-", 1).factory());
        this.componentRegistry = componentRegistry;
    }

    @Override
    protected <T> void handleGrpcExceptions(@NotNull final String actionName, @NotNull final StatusRuntimeException exception, @NotNull final CompletableFuture<T> future) {
        final var status = exception.getStatus();
        final var code = status.getCode();
        final var description = status.getDescription();

        switch (code) {
            case NOT_FOUND:
            case INVALID_ARGUMENT:
            case ABORTED:
                LOGGER.warn("Action '{}' rejected by backend (Code logic): {}", actionName, description);
                break;

            case DATA_LOSS:
            case UNAVAILABLE:
            case INTERNAL:
                LOGGER.error("Critical infrastructure failure during action '{}': {}", actionName, description, exception);
                break;

            default:
                LOGGER.error("Unexpected backend error during action '{}'", actionName, exception);
                break;
        }

        super.handleGrpcExceptions(actionName, exception, future);
    }


    public CompletableFuture<Void> takeDatabaseSnapshot(@NotNull final String playerUniqueId, @NotNull final String reason, @NotNull final SnapshotTriggerType triggerType) {
        return asyncRun(executor, LOGGER, "takeDatabaseSnapshot", () -> {
            BsonDocument requestDocument = new BsonDocument()
                    .append("uuid", new BsonString(playerUniqueId))
                    .append("reason", new BsonString(reason))
                    .append("trigger_type", new BsonString(triggerType.name()));

            ClientCalls.blockingUnaryCall(grpcClient.getChannel(), SnapshotContract.TAKE_DATABASE_SNAPSHOT,
                    CallOptions.DEFAULT.withDeadlineAfter(3, TimeUnit.SECONDS), BsonUtil.toRawBsonDocument(requestDocument));
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Record> CompletableFuture<Void> takeComponentSnapshot(@NotNull final String playerUniqueId, @NotNull final String reason,
                                                                            @NotNull final SnapshotTriggerType triggerType, @NotNull final T component) {
        return asyncRun(executor, LOGGER, "takeComponentSnapshot", () -> {
            final var codec = (ComponentCodec<T>) componentRegistry.getCodec(component.getClass());
            if (codec == null) {
                throw new IllegalArgumentException(String.format("Component Codec not found for '%s'", component.getClass()));
            }

            final var encodedComponent = codec.encode(component);
            final var requestDocument = new BsonDocument()
                    .append("uuid", new BsonString(playerUniqueId))
                    .append("target_component", new BsonString(codec.getComponentNamespace().toString()))
                    .append("data", encodedComponent)
                    .append("reason", new BsonString(reason))
                    .append("trigger_type", new BsonString(triggerType.name()));

            ClientCalls.blockingUnaryCall(grpcClient.getChannel(), SnapshotContract.TAKE_COMPONENT_SNAPSHOT,
                    CallOptions.DEFAULT.withDeadlineAfter(3, TimeUnit.SECONDS), BsonUtil.toRawBsonDocument(requestDocument));
        });

    }

    @Override
    public CompletableFuture<Boolean> toggleSnapshotLocking(@NotNull final String snapshotId, @NotNull final String executorUniqueId) {
        return asyncCall(executor, LOGGER, "toggleLockSnapshot", () -> {

            final var requestDocument = new BsonDocument()
                    .append("snapshot_id", new BsonString(snapshotId))
                    .append("locker_id", new BsonString(executorUniqueId));


            final var responseDocument = ClientCalls.blockingUnaryCall(grpcClient.getChannel(), SnapshotContract.TOGGLE_SNAPSHOT_LOCK,
                    CallOptions.DEFAULT.withDeadlineAfter(3, TimeUnit.SECONDS), BsonUtil.toRawBsonDocument(requestDocument));

            return responseDocument.getBoolean("locked").getValue();
        });
    }

    @Override
    public CompletableFuture<List<SnapshotDayInfo>> getSnapshotDays(@NotNull final String playerUniqueId) {
        Objects.requireNonNull(playerUniqueId, "playerUniqueId cannot be null");
        return asyncCall(executor, LOGGER, "getSnapshotDays", () -> {
            final var request = new BsonDocument("uuid", new BsonString(playerUniqueId));

            final var responseDocument = ClientCalls.blockingUnaryCall(
                    grpcClient.getChannel(),
                    SnapshotContract.LIST_SNAPSHOT_DAYS,
                    CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS),
                    BsonUtil.toRawBsonDocument(request)
            );

            final var list = new ArrayList<SnapshotDayInfo>();
            if (responseDocument.containsKey("days") && responseDocument.isArray("days")) {
                for (final var dayVal : responseDocument.getArray("days")) {
                    if (!dayVal.isDocument()) continue;
                    final var dayDocument = dayVal.asDocument();
                    final var date = LocalDate.parse(dayDocument.getString("date").getValue());
                    final var count = dayDocument.getInt32("count").getValue();
                    final var lastTimestamp = dayDocument.getInt64("last_timestamp").getValue();
                    list.add(new SnapshotDayInfo(date, count, lastTimestamp));
                }
            }
            return list;
        });
    }

    @Override
    public CompletableFuture<List<SnapshotInfo>> getSnapshotsForDay(@NotNull final String playerUniqueId, @NotNull final LocalDate date) {
        Objects.requireNonNull(playerUniqueId, "playerUniqueId cannot be null");
        Objects.requireNonNull(date, "date cannot be null");
        return asyncCall(executor, LOGGER, "getSnapshotsForDay", () -> {
            final var zone = ZoneId.systemDefault();
            final var startTimestamp = date.atStartOfDay(zone).toInstant().toEpochMilli();
            final var endTimestamp = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1;

            final var request = new BsonDocument()
                    .append("uuid", new BsonString(playerUniqueId))
                    .append("date", new BsonString(date.toString()))
                    .append("start_timestamp", new BsonInt64(startTimestamp))
                    .append("end_timestamp", new BsonInt64(endTimestamp));

            final var responseDocument = ClientCalls.blockingUnaryCall(
                    grpcClient.getChannel(),
                    SnapshotContract.GET_SNAPSHOTS_FOR_DAY,
                    CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS),
                    BsonUtil.toRawBsonDocument(request)
            );

            final var list = new ArrayList<SnapshotInfo>();
            if (responseDocument.containsKey("snapshots") && responseDocument.isArray("snapshots")) {
                for (final var snapVal : responseDocument.getArray("snapshots")) {
                    if (!snapVal.isDocument()) continue;
                    final var snapDoc = snapVal.asDocument();
                    list.add(new SnapshotInfo(
                            snapDoc.getString("snapshot_id").getValue(),
                            snapDoc.getInt64("timestamp").getValue(),
                            snapDoc.getString("reason").getValue(),
                            snapDoc.getString("source").getValue(),
                            SnapshotTriggerType.fromString(snapDoc.getString("trigger_type").getValue()),
                            snapDoc.getString("granularity").getValue(),
                            snapDoc.getBoolean("locked").getValue()
                    ));
                }
            }
            return list;
        });
    }

    @Override
    public CompletableFuture<Map<ComponentNamespace, Record>> decodeSnapshot(@NotNull final String snapshotId) {
        Objects.requireNonNull(snapshotId, "snapshotId cannot be null");
        return asyncCall(executor, LOGGER, "decodeSnapshot", () -> {
            final var request = new BsonDocument("snapshot_id", new BsonString(snapshotId));
            final var responseDocument = ClientCalls.blockingUnaryCall(
                    grpcClient.getChannel(),
                    SnapshotContract.DECODE_SNAPSHOT,
                    CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS),
                    BsonUtil.toRawBsonDocument(request)
            );

            final Map<ComponentNamespace, Record> map = new HashMap<>();
            final var components = responseDocument.containsKey("components") && responseDocument.isDocument("components")
                    ? responseDocument.getDocument("components")
                    : responseDocument;
            for (final var namespaceStr : components.keySet()) {
                if (!components.isDocument(namespaceStr)) {
                    LOGGER.warn("Component '{}' in snapshot '{}' is not a valid BSON document", namespaceStr, snapshotId);
                    continue;
                }

                final ComponentNamespace namespace;
                try {
                    namespace = ComponentNamespace.parse(namespaceStr);
                } catch (IllegalArgumentException exception) {
                    LOGGER.error("Invalid component namespace '{}' in snapshot '{}'", namespaceStr, snapshotId, exception);
                    continue;
                }

                final var codec = componentRegistry.getCodec(namespace);
                if (codec == null) {
                    LOGGER.warn("Unsupported component namespace '{}' in snapshot '{}' (no registered codec)", namespace, snapshotId);
                    continue;
                }

                try {
                    final var componentBson = components.getDocument(namespaceStr);
                    final var decoded = codec.decode(componentBson);
                    if (decoded != null) {
                        map.put(namespace, decoded);
                    }
                } catch (Exception exception) {
                    LOGGER.error("Failed to decode component '{}' in snapshot '{}'", namespace, snapshotId, exception);
                }
            }
            return Map.copyOf(map);
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Record> CompletableFuture<T> decodeSnapshotComponent(@NotNull String snapshotId, @NotNull ComponentNamespace namespace) {
        return decodeSnapshot(snapshotId).thenApply(map -> (T) map.get(namespace));
    }


    public void shutdown() {
        LOGGER.info("Shutdown initiated — draining remaining buffer...");

        executor.shutdown();

        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                LOGGER.error("Snapshot executor did not terminate within 10s — forcing shutdown.");
                executor.shutdownNow();
            }
            LOGGER.info("Shutdown complete.");
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            LOGGER.error("Shutdown interrupted — some data may be lost.", e);
        }
    }
}
