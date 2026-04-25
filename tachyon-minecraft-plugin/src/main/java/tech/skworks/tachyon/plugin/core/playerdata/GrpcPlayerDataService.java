package tech.skworks.tachyon.plugin.core.playerdata;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tech.skworks.tachyon.api.profile.TachyonProfile;
import tech.skworks.tachyon.api.services.PlayerDataService;
import tech.skworks.tachyon.libs.com.google.protobuf.Any;
import tech.skworks.tachyon.libs.io.grpc.Status;
import tech.skworks.tachyon.libs.io.grpc.StatusRuntimeException;
import tech.skworks.tachyon.plugin.spigot.TachyonCore;
import tech.skworks.tachyon.plugin.common.retry.RetryQueue;
import tech.skworks.tachyon.plugin.core.grpc.AbstractGrpcService;
import tech.skworks.tachyon.plugin.common.util.TachyonLogger;
import tech.skworks.tachyon.plugin.core.grpc.BackendStubProvider;
import tech.skworks.tachyon.plugin.core.metric.scraper.TachyonMetrics;
import tech.skworks.tachyon.plugin.common.retry.RetryTask;
import tech.skworks.tachyon.service.contracts.player.data.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/**
 * Project Tachyon
 * Class ComponentService
 *
 * @author  Jimmy (vSKAH) - 07/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class GrpcPlayerDataService extends AbstractGrpcService implements PlayerDataService {

    private static final TachyonLogger LOGGER = TachyonCore.getModuleLogger("ComponentService");
    private static final DateTimeFormatter LOG_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final Map<UUID, RetryQueue> retryQueues = new ConcurrentHashMap<>();
    private final ScheduledExecutorService retryScheduler;
    private final ExecutorService vThreadExecutor;

    private final File dataFolder;


    public GrpcPlayerDataService(BackendStubProvider backendStubProvider, File dataFolder, @Nullable TachyonMetrics tachyonMetrics) {
        super(tachyonMetrics, backendStubProvider);
        this.dataFolder = dataFolder;

        this.retryScheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("tachyon-playerdata-scheduler").factory());
        this.vThreadExecutor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("tachyon-playerdata-executor-", 1).factory());

        this.retryScheduler.scheduleAtFixedRate(this::processRetries, 2000L, 1200L, TimeUnit.MILLISECONDS);
    }

    @Override
    public @Nullable PullProfileResponse tryPullProfile(@NotNull final UUID uuid) {
        int attempts = 0;
        PullProfileResponse playerResponse = null;
        try (var _ = startTimer("GetPlayer")) {
            while (playerResponse == null && attempts < 6) {
                attempts++;
                try {
                    playerResponse = backendStubProvider.getPlayerDataStub(4).pullProfile(PullProfileRequest.newBuilder().setUuid(uuid.toString()).build());
                } catch (StatusRuntimeException e) {
                    Status status = e.getStatus();
                    if (status.getCode() == Status.Code.CANCELLED) {
                        String reason = status.getDescription() != null ? status.getDescription() : "LOCKED";
                        LOGGER.info("load() retry {}/4 for {} — reason: {}", attempts, uuid, reason);

                        if (attempts >= 4) {
                            LOGGER.error("load() exhausted 4 attempts (2 seconds) for {}. Player remains locked.", uuid);
                            return null;
                        }

                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            LOGGER.error("load() interrupted while waiting for {} to unlock", uuid);
                            return null;
                        }
                        continue;
                    }

                    LOGGER.error("load() gRPC infrastructure failure for {}: {}", uuid, status.getDescription());
                    recordError("GetPlayer_Network", e);
                    return null;
                }
            }
        } catch (Exception e) {
            LOGGER.error(e, "load() unexpected fatal failure for {}", uuid);
            recordError("GetPlayer_Fatal", e);
            return null;
        }

        return playerResponse;
    }


    public @NotNull CompletableFuture<Void> pushProfile(@NotNull final TachyonProfile tachyonProfile) {
        if (!tachyonProfile.hasPendingChanges()) return CompletableFuture.completedFuture(null);
        final CompletableFuture<Void> future = new CompletableFuture<>();

        final UUID uuid = tachyonProfile.getUuid();
        final String strUuid = uuid.toString();

        final Collection<Any> packed = tachyonProfile.extractDirtyComponents().stream().map(Any::pack).toList();
        final Collection<String> toRemove = tachyonProfile.extractDeletedComponentsUrls();
        final PushProfileRequest request = PushProfileRequest.newBuilder().setUuid(strUuid)
                .addAllComponentsToSave(packed)
                .addAllComponentsToRemove(toRemove).build();

        final int count = packed.size() + toRemove.size();

        RetryQueue queue = createQueue(uuid, new RetryTask(uuid) {
            @Override
            public boolean execute() {
                try (var _ = startTimer("SaveProfile")) {
                    backendStubProvider.getPlayerDataStub(4).pushProfile(request);
                    future.complete(null);
                    return true;

                } catch (StatusRuntimeException e) {
                    if (e.getStatus().getCode() == Status.Code.INVALID_ARGUMENT) {
                        LOGGER.error(e, "Invalid argument in save profile, aborting the process.");
                        future.completeExceptionally(e);
                        return true;
                    }

                    LOGGER.warn("Transient gRPC error in save profile for {} ({} components), retrying...", uuid, count);
                    recordError("SaveProfile_Transient", e);
                    return false;

                } catch (Exception e) {
                    LOGGER.error(e, "Unexpected exception in save profile, aborting the process.");
                    future.completeExceptionally(e);
                    recordError("SaveProfile_JVM", e);
                    return true;
                }
            }

            @Override
            public byte[] getPayload() {
                return request.toByteArray();
            }

            @Override
            public void onExhausted() {
                final String errorMsg = String.format("Task %s exhausted after %d attempts", describe(), getAttempts());
                LOGGER.error(errorMsg);
                future.completeExceptionally(new TimeoutException(errorMsg));
            }

            @Override
            public String describe() {
                return "SaveProfile(" + count + " components)";
            }
        });

        this.vThreadExecutor.submit(() -> queue.process(this::writeToDeadLetterFile));
        return future;
    }

    @Override
    protected <T> void handleGrpcExceptions(@NotNull final String actionName, @NotNull final StatusRuntimeException ex, final CompletableFuture<T> future) {
        if (future != null && !future.isDone()) {
            future.completeExceptionally(ex);
        }
    }


    private RetryQueue createQueue(@NotNull final UUID uuid, @NotNull final RetryTask task) {
        RetryQueue queue = retryQueues.computeIfAbsent(uuid, RetryQueue::new);
        queue.submit(task);
        return queue;
    }

    private void processRetries() {
        int total = 0;

        for (Map.Entry<UUID, RetryQueue> entry : retryQueues.entrySet()) {
            UUID uuid = entry.getKey();
            RetryQueue queue = entry.getValue();

            vThreadExecutor.submit(() -> {
                queue.process(this::writeToDeadLetterFile);

                if (queue.isEmpty()) {
                    retryQueues.remove(uuid);
                }
            });
            total += queue.size();
        }

        if (tachyonMetrics != null) tachyonMetrics.updateRetryQueueSize(total);
    }

    public void flushQueueForPlayer(@NotNull final UUID uuid) {
        RetryQueue queue = retryQueues.get(uuid);
        if (queue == null) return;

        queue.process(this::writeToDeadLetterFile);

        if (queue.isEmpty()) {
            retryQueues.remove(uuid);
        }
    }

    @Override
    public void shutdown() {
        LOGGER.info("Shutdown initiated: stopping schedulers and processing pending queues...");

        retryScheduler.shutdown();
        processRetries();
        vThreadExecutor.shutdown();

        try {
            if (!vThreadExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                LOGGER.warn("vThreadExecutor did not terminate within 30s — forcing shutdown.");
                vThreadExecutor.shutdownNow();
            }
            if (!retryScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                retryScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            vThreadExecutor.shutdownNow();
            retryScheduler.shutdownNow();
            Thread.currentThread().interrupt();
            LOGGER.error(e, "Shutdown interrupted while waiting for network tasks.");
        }

        retryQueues.forEach((uuid, queue) -> {
            if (!queue.isEmpty()) {
                LOGGER.error("[SHUTDOWN] {} task(s) could not be processed for {} — writing to dead-letter", queue.size(), uuid);
                queue.flushToRecovery(this::writeToDeadLetterFile);
            }
        });

        retryQueues.clear();
        LOGGER.info("Shutdown complete.");
    }

    private void writeToDeadLetterFile(final @NotNull RetryTask task) {
        final LocalDateTime now = LocalDateTime.now();
        final String logTimestamp = now.format(LOG_DATE_FORMAT);
        final String fileTimestamp = now.format(FILE_DATE_FORMAT);

        final String fileName = String.format("%s_%s_%s.bin", fileTimestamp, task.getUuid(), task.describe().replaceAll("[^a-zA-Z0-9]", "_"));

        try {
            final Path recoveryDir = dataFolder.toPath().resolve("recovery");
            Files.createDirectories(recoveryDir);

            final byte[] payload = task.getPayload();
            if (payload != null && payload.length > 0) {
                Files.write(recoveryDir.resolve("data").resolve(fileName), payload, StandardOpenOption.CREATE);
            }

            final Path logPath = dataFolder.toPath().resolve("recovey.log");
            final String logLine = String.format("[%s] FATAL: %s | Data saved to: %s%n", logTimestamp, task.getUuid(), fileName);
            Files.writeString(logPath, logLine, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            LOGGER.error("[RECOVERY] Data for {} saved to {}", task.getUuid(), fileName);
        } catch (IOException e) {
            LOGGER.error(e, "[CRITICAL] Failed to save recovery data for " + task.getUuid());
        }
    }

    public boolean hasRecoveryBinary() {
        final Path datasFolder = dataFolder.toPath().resolve("recovery").resolve("data");
        boolean folderExists = Files.exists(datasFolder) && Files.isDirectory(datasFolder);
        if (!folderExists) return false;
        try (DirectoryStream<Path> directory = Files.newDirectoryStream(datasFolder, "*bin")) {
            return !directory.iterator().hasNext();
        } catch (IOException e) {
           return false;
        }
    }
}
