package tech.skworks.tachyon.core.playerdata;

import io.grpc.CallOptions;
import io.grpc.stub.ClientCalls;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.RawBsonDocument;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.skworks.tachyon.common.BsonUtil;
import tech.skworks.tachyon.common.contract.PlayerDataContract;
import tech.skworks.tachyon.common.player.PlayerDelta;
import tech.skworks.tachyon.core.grpc.GrpcClient;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 * @author Jimmy (vSKAH) - 12/09/2026
 * @version 2.0
 * @since 2.0.0-alpha.2
 */
public class PlayerDataDlqService {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tachyon/PlayerDataDLQ");
    private static final int BATCH_SIZE = 30;

    private final Path datasDir;
    private final Path corruptedDataDir;

    private final GrpcClient grpcClient;
    private final ExecutorService vThreadExecutor;
    private final Map<UUID, PlayerDelta> staging;
    private final AtomicBoolean running;

    public PlayerDataDlqService(@NotNull final File dataFolder, @NotNull final GrpcClient grpcClient) {
        final var recoveryDir = Objects.requireNonNull(dataFolder, "dataFolder cannot be null").toPath().resolve("recovery");

        this.datasDir = recoveryDir.resolve("players-data");
        this.corruptedDataDir = recoveryDir.resolve("corrupted");

        this.grpcClient = Objects.requireNonNull(grpcClient, "grpcClient cannot be null");
        this.vThreadExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("tachyon-dlq-worker-", 1).factory()
        );
        this.staging = new ConcurrentHashMap<>();
        this.running = new AtomicBoolean(false);

        initStorages();
    }

    private void initStorages() {
        try {
            Files.createDirectories(this.datasDir);
            Files.createDirectories(this.corruptedDataDir);
        } catch (IOException e) {
            LOGGER.error("Failed to create DLQ recovery directories", e);
        }
    }

    private void save(@NotNull final PlayerDelta playerDelta) {
        final var tmpFile = datasDir.resolve(playerDelta.getPlayerUuid() + ".tmp");
        final var finalFile = datasDir.resolve(playerDelta.getPlayerUuid() + ".bson");

        try {
            final var bytes = BsonUtil.toByteArray(playerDelta.toRawBsonDocument());
            Files.write(tmpFile, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmpFile, finalFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.error("Failed to save DLQ file for {}", playerDelta.getPlayerUuid(), e);
        }
    }

    private @Nullable PlayerDelta load(@NotNull final UUID uuid) {
        final var file = datasDir.resolve(uuid + ".bson");
        if (!Files.exists(file)) {
            return null;
        }
        try {
            final var bytes = Files.readAllBytes(file);
            final var document = new RawBsonDocument(bytes);
            return PlayerDelta.fromBson(document);
        } catch (Exception e) {
            LOGGER.error("Failed to read DLQ file for {}, moving to corrupted", uuid, e);
            setCorrupted(file);
            return null;
        }
    }

    private void setCorrupted(@NotNull final Path file) {
        try {
            Files.move(file, corruptedDataDir.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.error("Failed to move corrupted file {}", file.getFileName(), e);
        }
    }

    public @Nullable PlayerDelta getPending(@NotNull final UUID uuid) {
        Objects.requireNonNull(uuid, "uuid cannot be null");
        final var loadedEntry = staging.get(uuid);
        return loadedEntry != null ? loadedEntry : load(uuid);
    }

    public void enqueue(@NotNull final PlayerDelta newDelta) {
        Objects.requireNonNull(newDelta, "newDelta cannot be null");
        final var entry = staging.compute(newDelta.getPlayerUuid(), (id, current) -> {
            final var value = (current != null) ? current : load(id);
            if (value == null) {
                return newDelta;
            }
            value.merge(newDelta);
            return value;
        });

        vThreadExecutor.execute(() -> save(entry));
    }

    public void enqueue(@NotNull final UUID uuid, @NotNull final RawBsonDocument payload) {
        Objects.requireNonNull(uuid, "uuid cannot be null");
        Objects.requireNonNull(payload, "payload cannot be null");

        final var entry = staging.compute(uuid, (id, current) -> {
            final var value = (current != null) ? current : load(id);
            final var target = (value != null) ? value : new PlayerDelta(id);
            target.applyDeltaDocument(payload);
            return target;
        });

        vThreadExecutor.execute(() -> save(entry));
    }

    public void replayAll() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        vThreadExecutor.execute(() -> {
            try {
                final var files = new ArrayList<Path>();
                try (final var stream = Files.newDirectoryStream(datasDir, "*.bson")) {
                    stream.forEach(files::add);
                } catch (IOException e) {
                    LOGGER.error("Failed to scan DLQ directory", e);
                    return;
                }

                if (files.isEmpty()) {
                    return;
                }
                LOGGER.info("Starting DLQ replay for {} profile(s)", files.size());

                for (int i = 0; i < files.size(); i += BATCH_SIZE) {
                    final var batchFiles = files.subList(i, Math.min(i + BATCH_SIZE, files.size()));
                    final var batchArray = new BsonArray();
                    final var batchEntries = new ArrayList<ReplayEntry>(batchFiles.size());

                    for (final var file : batchFiles) {
                        final var fileName = file.getFileName().toString();
                        final UUID uuid;
                        try {
                            uuid = UUID.fromString(fileName.replace(".bson", ""));
                        } catch (IllegalArgumentException e) {
                            LOGGER.warn("Invalid UUID filename in DLQ: {}, moving to corrupted", fileName);
                            setCorrupted(file);
                            continue;
                        }

                        final var entry = getPending(uuid);
                        if (entry != null) {
                            final long lastEdit = entry.getLastModifiedTimestamp();
                            batchArray.add(entry.toRawBsonDocument());
                            batchEntries.add(new ReplayEntry(entry, lastEdit, file));
                        }
                    }

                    if (batchArray.isEmpty()) {
                        continue;
                    }

                    final var batchPayload = BsonUtil.toRawBsonDocument(new BsonDocument("profiles", batchArray));

                    try {
                        ClientCalls.blockingUnaryCall(
                                grpcClient.getChannel(),
                                PlayerDataContract.PUSH_DLQ_BATCH_METHOD,
                                CallOptions.DEFAULT.withDeadlineAfter(10, TimeUnit.SECONDS),
                                batchPayload
                        );

                        for (final var item : batchEntries) {
                            if (item.delta().getLastModifiedTimestamp() == item.lastModifiedTimestamp()) {
                                staging.remove(item.delta().getPlayerUuid(), item.delta());
                                try {
                                    Files.deleteIfExists(item.file());
                                } catch (IOException e) {
                                    LOGGER.warn("Failed to delete replayed DLQ file for {}", item.file().getFileName(), e);
                                }
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.warn("DLQ replay failed (backend may still be down): {}. Retrying later.", e.getMessage());
                        return;
                    }
                }
            } finally {
                running.set(false);
            }
        });
    }

    public void shutdown() {
        vThreadExecutor.shutdown();
        try {
            if (!vThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                vThreadExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            vThreadExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private record ReplayEntry(@NotNull PlayerDelta delta, long lastModifiedTimestamp, @NotNull Path file) {
    }
}
