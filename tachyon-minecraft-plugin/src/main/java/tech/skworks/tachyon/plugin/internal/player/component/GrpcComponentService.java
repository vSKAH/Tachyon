package tech.skworks.tachyon.plugin.internal.player.component;

import tech.skworks.tachyon.service.contracts.player.*;
import tech.skworks.tachyon.libs.com.google.protobuf.Any;
import tech.skworks.tachyon.libs.com.google.protobuf.Message;
import tech.skworks.tachyon.libs.io.grpc.Status;
import tech.skworks.tachyon.libs.io.grpc.StatusRuntimeException;
import tech.skworks.tachyon.plugin.spigot.TachyonCore;
import tech.skworks.tachyon.plugin.internal.retry.RetryQueue;
import tech.skworks.tachyon.plugin.internal.util.AbstractGrpcService;
import tech.skworks.tachyon.plugin.internal.util.TachyonLogger;
import tech.skworks.tachyon.plugin.internal.GrpcClientManager;
import tech.skworks.tachyon.plugin.internal.metric.scraper.TachyonMetrics;
import tech.skworks.tachyon.plugin.internal.retry.RetryTask;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
public class GrpcComponentService extends AbstractGrpcService {

    private static final TachyonLogger LOGGER = TachyonCore.getModuleLogger("ComponentService");

    private final Map<UUID, RetryQueue> retryQueues = new ConcurrentHashMap<>();
    private final ScheduledExecutorService retryScheduler;
    private final File dataFolder;

    public GrpcComponentService(GrpcClientManager grpcClientManager, File dataFolder, @Nullable TachyonMetrics tachyonMetrics) {
        super(tachyonMetrics, grpcClientManager);
        this.dataFolder = dataFolder;
        this.retryScheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("tachyon-retry-scheduler").factory());
        this.retryScheduler.scheduleAtFixedRate(this::processAllRetries, 2000L, 1200L, TimeUnit.MILLISECONDS);
    }

    @Nullable
    public GetPlayerResponse loadProfile(UUID uuid) {
        int attempts = 0;
        GetPlayerResponse playerResponse = null;
        try (var _ = startTimer("GetPlayer")) {
            while (playerResponse == null && attempts < 6) {
                attempts++;
                try {
                    playerResponse = grpcClientManager.getPlayerStub(4).getPlayer(GetPlayerRequest.newBuilder().setUuid(uuid.toString()).build());
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


    @Override
    protected <T> void handleGrpcExceptions(String actionName, StatusRuntimeException ex, CompletableFuture<T> future) {
    }

    public CompletableFuture<Void> saveProfile(UUID uuid, Collection<Message> components) {
        if (components.isEmpty()) return CompletableFuture.completedFuture(null);

        final SaveProfileRequest request = components.stream().reduce(SaveProfileRequest.newBuilder().setUuid(uuid.toString()), (b, c) -> b.addComponents(Any.pack(c)), (a, b) -> a).build();
        final CompletableFuture<Void> future = new CompletableFuture<>();
        final int count = components.size();

        submit(uuid, new RetryTask(uuid) {
            @Override
            public boolean execute() {
                try (var _ = startTimer("SaveProfile")) {
                    grpcClientManager.getPlayerStub(4).saveProfile(request);

                    future.complete(null);
                    return true;

                } catch (StatusRuntimeException e) {
                    //TODO: invalid argument on quarkus
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
                    recordError("SaveProfile_Fatal", e);
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

        return future;
    }

    public <T extends Message> CompletableFuture<Void> saveComponent(UUID uuid, T component) {
        final Any packed = Any.pack(component);
        final String typeUrl = typeUrlOf(component);
        final CompletableFuture<Void> future = new CompletableFuture<>();

        submit(uuid, new RetryTask(uuid) {
            @Override
            public boolean execute() {
                try (var _ = startTimer("SaveComponent")) {
                    grpcClientManager.getPlayerStub(4).saveComponent(SaveComponentRequest.newBuilder().setUuid(uuid.toString()).setComponent(packed).build());

                    future.complete(null);
                    return true;

                } catch (StatusRuntimeException e) {
                    //todo: throws invalid argument quarkus
                    if (e.getStatus().getCode() == Status.Code.INVALID_ARGUMENT) {
                        LOGGER.error(e, "Invalid argument in save component, aborting the process.");
                        future.completeExceptionally(e);
                        return true;
                    }

                    LOGGER.warn("Transient gRPC error in save component for {}, retrying...", uuid);
                    recordError("SaveComponent_Transient", e);
                    return false;

                } catch (Exception e) {
                    LOGGER.error(e, "Unexpected exception in save component, aborting the process.");
                    future.completeExceptionally(e);
                    recordError("SaveComponent_Fatal", e);
                    return true;
                }
            }

            @Override
            public byte[] getPayload() {
                return packed.toByteArray();
            }

            @Override
            public void onExhausted() {
                final String errorMsg = String.format("Task %s exhausted after %d attempts", describe(), getAttempts());
                LOGGER.error(errorMsg);
                future.completeExceptionally(new TimeoutException(errorMsg));
            }

            @Override
            public String describe() {
                return "SaveComponent(" + typeUrl + ")";
            }
        });

        return future;
    }

    public <T extends Message> CompletableFuture<Void> deleteComponent(UUID uuid, T component) {
        final String url = typeUrlOf(component);
        final CompletableFuture<Void> future = new CompletableFuture<>();

        submit(uuid, new RetryTask(uuid) {
            @Override
            public boolean execute() {
                try (var _ = startTimer("DeleteComponent")) {
                    grpcClientManager.getPlayerStub(4).deleteComponent(DeleteComponentRequest.newBuilder().setUuid(uuid.toString()).setComponentUrl(url).build());
                    future.complete(null);
                    return true;

                } catch (StatusRuntimeException e) {
                    //todo: throws invalid argument on quarkus
                    if (e.getStatus().getCode() == Status.Code.INVALID_ARGUMENT) {
                        LOGGER.error(e, "Invalid argument in delete component, aborting the process.");
                        future.completeExceptionally(e);
                        return true;
                    }

                    LOGGER.warn("Transient gRPC error in delete component for {}, retrying...", uuid);
                    recordError("DeleteComponent_Transient", e);
                    return false;

                } catch (Exception e) {
                    LOGGER.error(e, "Unexpected exception in delete component, aborting the process.");
                    future.completeExceptionally(e);
                    recordError("DeleteComponent_Fatal", e);
                    return true;
                }
            }

            @Override
            public byte[] getPayload() {
                return url.getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public void onExhausted() {
                final String errorMsg = String.format("Task %s exhausted after %d attempts", describe(), getAttempts());
                LOGGER.error(errorMsg);
                future.completeExceptionally(new TimeoutException(errorMsg));
            }

            @Override
            public String describe() {
                return "DeleteComponent(" + url + ")";
            }
        });

        return future;
    }

    private void submit(UUID uuid, RetryTask task) {
        retryQueues.computeIfAbsent(uuid, RetryQueue::new).submit(task, this::writeToDeadLetterFile);
    }

    private void processAllRetries() {
        int total = 0;

        var iterator = retryQueues.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            RetryQueue queue = entry.getValue();

            queue.process(this::writeToDeadLetterFile);

            int size = queue.size();
            if (size == 0) {
                iterator.remove();
            } else {
                total += size;
            }
        }

        if (tachyonMetrics != null) tachyonMetrics.updateRetryQueueSize(total);
    }

    public void flushQueueForPlayer(UUID uuid) {
        RetryQueue queue = retryQueues.get(uuid);
        if (queue == null) return;

        queue.process(this::writeToDeadLetterFile);

        if (queue.isEmpty()) {
            retryQueues.remove(uuid);
        }
    }

    public void shutdown() {
        retryScheduler.shutdown();
        LOGGER.info("Shutdown: processing all pending queues...");

        retryQueues.values().forEach(queue -> queue.process(this::writeToDeadLetterFile));

        retryQueues.forEach((uuid, queue) -> {
            if (!queue.isEmpty()) {
                LOGGER.error("[SHUTDOWN] {} task(s) could not be processed for {} — writing to dead-letter", queue.size(), uuid);
                queue.drainToDeadLetter(this::writeToDeadLetterFile);
            }
        });

        retryQueues.clear();
        LOGGER.info("Shutdown complete.");
    }

    private static final DateTimeFormatter LOG_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private void writeToDeadLetterFile(final @Nonnull RetryTask task) {
        final LocalDateTime now = LocalDateTime.now();
        final String logTimestamp = now.format(LOG_DATE_FORMAT);
        final String fileTimestamp = now.format(FILE_DATE_FORMAT);

        final String fileName = String.format("%s_%s_%s.bin", fileTimestamp, task.getUuid(), task.describe().replaceAll("[^a-zA-Z0-9]", "_"));

        try {
            final Path recoveryDir = dataFolder.toPath().resolve("recovery");
            Files.createDirectories(recoveryDir);

            final byte[] payload = task.getPayload();
            if (payload != null && payload.length > 0) {
                Files.write(recoveryDir.resolve(fileName), payload, StandardOpenOption.CREATE);
            }

            final Path logPath = dataFolder.toPath().resolve("recovery.log");
            final String logLine = String.format("[%s] FATAL: %s | Data saved to: %s%n", logTimestamp, task.getUuid(), fileName);
            Files.writeString(logPath, logLine, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            LOGGER.error("[RECOVERY] Data for {} saved to {}", task.getUuid(), fileName);
        } catch (IOException e) {
            LOGGER.error(e, "[CRITICAL] Failed to save recovery data for " + task.getUuid());
        }
    }

    private static String typeUrlOf(Message m) {
        return "type.googleapis.com/" + m.getDescriptorForType().getFullName();
    }
}
