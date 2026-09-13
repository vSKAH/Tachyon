package tech.skworks.tachyon.plugin.core.playerdata;

import io.grpc.CallOptions;
import io.grpc.stub.ClientCalls;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.RawBsonDocument;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tech.skworks.tachyon.common.BsonUtil;
import tech.skworks.tachyon.common.contract.PlayerDataContract;
import tech.skworks.tachyon.plugin.core.grpc.GrpcClient;
import tech.skworks.tachyon.plugin.core.util.TachyonLogger;
import tech.skworks.tachyon.plugin.spigot.TachyonCore;

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
 * @version 1.0
 * @since 2.0.0-alpha.2
 */
public class PlayerDataDlqService {

    private static final TachyonLogger LOGGER = TachyonCore.getModuleLogger("PlayerDataDLQ");
    private static final int BATCH_SIZE = 30;

    private final Path datasDir;
    private final Path corruptedDataDir;

    private final GrpcClient grpcClient;
    private final ExecutorService vThreadExecutor;
    private final Map<UUID, PlayerDataEntry> stagging;
    private final AtomicBoolean running;

    public PlayerDataDlqService(@NotNull final File dataFolder, @NotNull final GrpcClient grpcClient) {
        final var recoveryDir = Objects.requireNonNull(dataFolder, "dataFolder cannot be null").toPath().resolve("recovery");

        this.datasDir = recoveryDir.resolve("players-data");
        this.corruptedDataDir = recoveryDir.resolve("corrupted");

        this.grpcClient = Objects.requireNonNull(grpcClient, "grpcClient cannot be null");
        this.vThreadExecutor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("tachyon-dlq-worker-", 1).factory());
        this.stagging = new ConcurrentHashMap<>();
        this.running = new AtomicBoolean(false);

        initStorages();
    }
    private void initStorages() {
        try {
            Files.createDirectories(this.datasDir);
            Files.createDirectories(this.corruptedDataDir);
        } catch (IOException e) {
            LOGGER.error(e, "Failed to create DLQ recovery directories");
        }
    }

    private void save(@NotNull final PlayerDataEntry playerDataEntry) {
        final var tmpFile = datasDir.resolve(playerDataEntry.getUuid() + ".tmp");
        final var finalFile = datasDir.resolve(playerDataEntry.getUuid() + ".bson");

        try {
            final var bytes = BsonUtil.toByteArray(playerDataEntry.build());
            Files.write(tmpFile, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmpFile, finalFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.error(e, "Failed to save DLQ file for {}", playerDataEntry.getUuid());
        }
    }

    private @Nullable PlayerDataEntry load(@NotNull final UUID uuid) {
        final var file = datasDir.resolve(uuid + ".bson");
        if (!Files.exists(file)) {
            return null;
        }
        try {
            final var bytes = Files.readAllBytes(file);
            final var document = new RawBsonDocument(bytes);
            final var entry = new PlayerDataEntry(uuid);
            entry.merge(document);
            return entry;
        } catch (Exception e) {
            LOGGER.error(e, "Failed to read DLQ file for {}, moving to corrupted", uuid);
            setCorrupted(file);
            return null;
        }
    }

    private void setCorrupted(@NotNull final Path file) {
        try {
            Files.move(file, corruptedDataDir.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.error(e, "Failed to move corrupted file {}", file.getFileName());
        }
    }

    public @Nullable PlayerDataEntry getPending(@NotNull final UUID uuid) {
        Objects.requireNonNull(uuid, "uuid cannot be null");
        final var loadedEntry = stagging.get(uuid);
        return loadedEntry != null ? loadedEntry : load(uuid);
    }

    public void enqueue(@NotNull final PlayerDataEntry newEntry) {
        Objects.requireNonNull(newEntry, "newEntry cannot be null");
        final var entry = stagging.compute(newEntry.getUuid(), (id, current) -> {
            var value = (current != null) ? current : load(id);
            if (value == null) {
                return newEntry;
            }
            value.merge(newEntry);
            return value;
        });

        vThreadExecutor.execute(() -> save(entry));
    }

    public void enqueue(@NotNull final UUID uuid, @NotNull final RawBsonDocument payload) {
        Objects.requireNonNull(uuid, "uuid cannot be null");
        Objects.requireNonNull(payload, "payload cannot be null");

        final var entry = stagging.compute(uuid, (id, current) -> {
            var value = (current != null) ? current : load(uuid);
            if (value == null) {
                value = new PlayerDataEntry(id);
            }
            value.merge(payload);
            return value;
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
                    LOGGER.error(e, "Failed to scan DLQ directory");
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
                            final long lastEdit = entry.getLastEdit();
                            batchArray.add(entry.build());
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
                            if (item.entry().getLastEdit() == item.lastEdit()) {
                                stagging.remove(item.entry().getUuid(), item.entry());
                                try {
                                    Files.deleteIfExists(item.file());
                                } catch (IOException e) {
                                    LOGGER.warn(e, "Failed to delete replayed DLQ file for {}", item.file().getFileName());
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

    private record ReplayEntry(@NotNull PlayerDataEntry entry, long lastEdit, @NotNull Path file) {}

}
