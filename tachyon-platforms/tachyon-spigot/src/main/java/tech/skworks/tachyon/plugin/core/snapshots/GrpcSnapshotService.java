package tech.skworks.tachyon.plugin.core.snapshots;

import io.grpc.CallOptions;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCalls;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tech.skworks.tachyon.api.component.ComponentCodec;
import tech.skworks.tachyon.api.component.ComponentNamespace;
import tech.skworks.tachyon.api.component.ComponentRegistry;
import tech.skworks.tachyon.api.snapshot.SnapshotInfo;
import tech.skworks.tachyon.api.snapshot.SnapshotService;
import tech.skworks.tachyon.api.snapshot.SnapshotTriggerType;
import tech.skworks.tachyon.common.BsonUtil;
import tech.skworks.tachyon.common.contract.SnapshotContract;
import tech.skworks.tachyon.plugin.spigot.TachyonCore;
import tech.skworks.tachyon.plugin.core.grpc.GrpcClient;
import tech.skworks.tachyon.plugin.core.metric.scraper.TachyonMetrics;
import tech.skworks.tachyon.plugin.core.grpc.AbstractGrpcService;
import tech.skworks.tachyon.plugin.core.util.TachyonLogger;

import org.bson.BsonInt64;
import tech.skworks.tachyon.api.snapshot.SnapshotDayInfo;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tech.skworks.tachyon.plugin.core.resilience.ResilienceService;
import java.util.concurrent.*;

/**
 * Project Tachyon
 * Class GrpcSnapshotService
 *
 * @author  Jimmy (vSKAH) - 17/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class GrpcSnapshotService extends AbstractGrpcService implements SnapshotService {

    private static final TachyonLogger LOGGER = TachyonCore.getModuleLogger("SnapshotService");
    private final ExecutorService executor;
    private final ComponentRegistry componentRegistry;

    public GrpcSnapshotService(@NotNull ResilienceService resilience, @Nullable TachyonMetrics tachyonMetrics, GrpcClient grpcClient, ComponentRegistry componentRegistry) {
        super("snapshot", resilience, tachyonMetrics, grpcClient);
        this.executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("snapshot-vthread-", 1).factory());
        this.componentRegistry = componentRegistry;
    }

    @Override
    protected <T> void handleGrpcExceptions(@NotNull final String actionName, @NotNull final StatusRuntimeException ex, final CompletableFuture<T> future) {
        final Status.Code code = ex.getStatus().getCode();
        final String description = ex.getStatus().getDescription();

        switch (code) {
            case NOT_FOUND:
            case INVALID_ARGUMENT:
            case ABORTED:
                LOGGER.warn("Action '{}' rejected by backend (Code logic): {}", actionName, description);
                break;

            case DATA_LOSS:
            case UNAVAILABLE:
            case INTERNAL:
                LOGGER.error(ex, "Critical infrastructure failure during action '{}': {}", actionName, description);
                break;

            default:
                LOGGER.error(ex, "Unexpected backend error during action '{}'", actionName);
                break;
        }

        if (future != null && !future.isDone()) {
            future.completeExceptionally(ex);
        }
    }


    public CompletableFuture<Void> takeDatabaseSnapshot(@NotNull final String playerUniqueId, @NotNull final String reason, @NotNull SnapshotTriggerType triggerType) {
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
    public <T extends Record> CompletableFuture<Void> takeComponentSnapshot(@NotNull final String playerUniqueId, @NotNull final String reason,
                                                                             @NotNull final SnapshotTriggerType triggerType, @NotNull final T component) {
        return asyncRun(executor, LOGGER, "takeComponentSnapshot", () -> {
            ComponentCodec<T> codec = (ComponentCodec<T>) componentRegistry.getCodec(component.getClass());
            if (codec == null ) {
                throw new IllegalArgumentException(String.format("Component Codec not found for '%s'", component.getClass()));
            }

            var encodedComponent = codec.encode(component);
            var requestDocument = new BsonDocument()
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
                    final var dayDoc = dayVal.asDocument();
                    final var date = LocalDate.parse(dayDoc.getString("date").getValue());
                    final var count = dayDoc.getInt32("count").getValue();
                    final var lastTs = dayDoc.getInt64("last_timestamp").getValue();
                    list.add(new SnapshotDayInfo(date, count, lastTs));
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
            final long startTs = date.atStartOfDay(zone).toInstant().toEpochMilli();
            final long endTs = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1;

            final var request = new BsonDocument()
                    .append("uuid", new BsonString(playerUniqueId))
                    .append("date", new BsonString(date.toString()))
                    .append("start_timestamp", new BsonInt64(startTs))
                    .append("end_timestamp", new BsonInt64(endTs));

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

            for (final String namespaceStr : components.keySet()) {
                    if (!components.isDocument(namespaceStr)) {
                        LOGGER.warn("Component '{}' in snapshot '{}' is not a valid BSON document", namespaceStr, snapshotId);
                        continue;
                    }

                    final ComponentNamespace namespace;
                    try {
                        namespace = ComponentNamespace.parse(namespaceStr);
                    } catch (IllegalArgumentException e) {
                        LOGGER.error("Invalid component namespace '{}' in snapshot '{}'", namespaceStr, snapshotId, e);
                        continue;
                    }

                    final var codec = componentRegistry.getCodec(namespace);
                    if (codec == null) {
                        LOGGER.warn("Unsupported component namespace '{}' in snapshot '{}' (no registered codec)", namespace, snapshotId);
                        continue;
                    }

                    try {
                        final var componentBson = components.getDocument(namespaceStr);
                        final Record decoded = codec.decode(componentBson);
                        if (decoded != null) {
                            map.put(namespace, decoded);
                        }
                    } catch (Exception e) {
                        LOGGER.error("Failed to decode component '{}' in snapshot '{}'", namespace, snapshotId, e);
                    }
                }
                return Map.copyOf(map);
            });
    }

    @Override
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
            LOGGER.error(e, "Shutdown interrupted — some data may be lost.");
        }
    }
}
