package tech.skworks.tachyon.plugin.core.snapshots;

import io.grpc.CallOptions;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCalls;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tech.skworks.tachyon.api.component.ComponentCodec;
import tech.skworks.tachyon.api.component.ComponentNamespace;
import tech.skworks.tachyon.api.component.ComponentRegistry;
import tech.skworks.tachyon.api.snapshot.SnapshotInfo;
import tech.skworks.tachyon.api.snapshot.SnapshotService;
import tech.skworks.tachyon.api.snapshot.SnapshotTriggerType;
import tech.skworks.tachyon.common.contract.SnapshotContract;
import tech.skworks.tachyon.common.marshaller.BsonMarshaller;
import tech.skworks.tachyon.plugin.spigot.TachyonCore;
import tech.skworks.tachyon.plugin.core.grpc.BackendStubProvider;
import tech.skworks.tachyon.plugin.core.metric.scraper.TachyonMetrics;
import tech.skworks.tachyon.plugin.core.grpc.AbstractGrpcService;
import tech.skworks.tachyon.plugin.common.util.TachyonLogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    public GrpcSnapshotService(@Nullable TachyonMetrics tachyonMetrics, BackendStubProvider backendStubProvider, ComponentRegistry componentRegistry) {
        super(tachyonMetrics, backendStubProvider);
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
                    .append("triggerType", new BsonString(triggerType.name()));

            ClientCalls.blockingUnaryCall(backendStubProvider.getChannel(), SnapshotContract.TAKE_DATABASE_SNAPSHOT,
                    CallOptions.DEFAULT.withDeadlineAfter(3, TimeUnit.SECONDS), BsonMarshaller.toRawBsonDocument(requestDocument));
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

            ClientCalls.blockingUnaryCall(backendStubProvider.getChannel(), SnapshotContract.TAKE_COMPONENT_SNAPSHOT,
                    CallOptions.DEFAULT.withDeadlineAfter(3, TimeUnit.SECONDS), BsonMarshaller.toRawBsonDocument(requestDocument));
        });

    }

    @Override
    public CompletableFuture<Boolean> toggleSnapshotLocking(@NotNull final String snapshotId, @NotNull final String executorUniqueId) {
        return asyncCall(executor, LOGGER, "toggleLockSnapshot", () -> {

            final var requestDocument = new BsonDocument()
                    .append("snapshot_id", new BsonString(snapshotId))
                    .append("locker_id", new BsonString(executorUniqueId));



            final var responseDocument = ClientCalls.blockingUnaryCall(backendStubProvider.getChannel(), SnapshotContract.TOGGLE_SNAPSHOT_LOCK,
                    CallOptions.DEFAULT.withDeadlineAfter(3, TimeUnit.SECONDS), BsonMarshaller.toRawBsonDocument(requestDocument));

            return responseDocument.getBoolean("locked").getValue();
        });
    }

    @Override
    public CompletableFuture<List<SnapshotInfo>> getSnapshots(@NotNull final String playerUniqueId) {
        return asyncCall(executor, LOGGER, "getSnapshots", () -> {

            final var request = new BsonDocument("uuid", new BsonString(playerUniqueId));

            final var responseDocument = ClientCalls.blockingUnaryCall(backendStubProvider.getChannel(), SnapshotContract.LIST_SNAPSHOT,
                    CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS), BsonMarshaller.toRawBsonDocument(request));

            List<SnapshotInfo> list = new ArrayList<>();

            if (responseDocument.containsKey("snapshots") && responseDocument.isArray("snapshots")) {
                list = new ArrayList<>(responseDocument.getArray("snapshots").size());
                for (BsonValue snapshots : responseDocument.getArray("snapshots")) {
                    BsonDocument snapshotDocument = snapshots.asDocument();
                    list.add(new SnapshotInfo(
                            snapshotDocument.getString("snapshot_id").getValue(),
                            snapshotDocument.getInt64("timestamp").getValue(),
                            snapshotDocument.getString("reason").getValue(),
                            snapshotDocument.getString("source").getValue(),
                            SnapshotTriggerType.fromString(snapshotDocument.getString("trigger_type").getValue()),
                            snapshotDocument.getString("granularity").getValue(),
                            snapshotDocument.getBoolean("locked").getValue()
                            ));
                }
            }

            return list;
        });
    }

    public <T extends Record> CompletableFuture<Map<ComponentNamespace, T>> decodeSnapshot(@NotNull final String snapshotId)
    {
        return asyncCall(executor, LOGGER, "decodeSnapshot", () -> {
            final var request = new BsonDocument("snapshot_id", new BsonString(snapshotId));
            final var responseDocument = ClientCalls.blockingUnaryCall(backendStubProvider.getChannel(), SnapshotContract.DECODE_SNAPSHOT,
                    CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS), BsonMarshaller.toRawBsonDocument(request));


            Map<ComponentNamespace, T> map = new HashMap<>();
            if (responseDocument.containsKey("components") && responseDocument.isDocument("components")) {
                final var components = responseDocument.getDocument("components");
                for (String namespaceStr : components.keySet()) {
                    final var component = components.getDocument(namespaceStr);
                    final var namespace = ComponentNamespace.parse(namespaceStr);

                    final var codec = componentRegistry.getCodec(namespace);
                    if (codec == null) {
                        LOGGER.error("Unsupported component namespace '{}'", namespace);
                        continue;
                    }

                    map.put(namespace, (T) codec.decode(component));
                }
            }
            return map;
        });
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
