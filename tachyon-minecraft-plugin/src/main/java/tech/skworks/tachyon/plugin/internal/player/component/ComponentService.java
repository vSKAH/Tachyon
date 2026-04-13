package tech.skworks.tachyon.plugin.internal.player.component;

import tech.skworks.tachyon.contracts.common.StandardResponse;
import tech.skworks.tachyon.contracts.player.*;
import tech.skworks.tachyon.libs.protobuf.Any;
import tech.skworks.tachyon.libs.protobuf.Message;
import tech.skworks.tachyon.plugin.TachyonCore;
import tech.skworks.tachyon.plugin.internal.player.retry.RetryQueue;
import tech.skworks.tachyon.plugin.internal.util.AbstractGrpcService;
import tech.skworks.tachyon.plugin.internal.util.TachyonLogger;
import tech.skworks.tachyon.plugin.internal.grpc.GrpcClientManager;
import tech.skworks.tachyon.plugin.internal.metric.scraper.TachyonMetrics;
import tech.skworks.tachyon.plugin.internal.player.retry.RetryTask;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
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
public class ComponentService extends AbstractGrpcService {

    private static final TachyonLogger LOGGER = TachyonCore.getModuleLogger("ComponentService");

    private final Map<UUID, RetryQueue> retryQueues = new ConcurrentHashMap<>();
    private final ScheduledExecutorService retryScheduler;
    private final File dataFolder;


    public ComponentService(GrpcClientManager grpcClientManager, File dataFolder, @Nullable TachyonMetrics tachyonMetrics) {
        super(tachyonMetrics, grpcClientManager);
        this.dataFolder = dataFolder;
        this.retryScheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("tachyon-retry-", 1).factory());
        this.retryScheduler.scheduleAtFixedRate(this::processAllRetries, 2, 1, TimeUnit.SECONDS);
    }

    @Nullable
    public PlayerResponse loadProfile(UUID uuid) {
        int attempts = 0;
        try (var timer = startTimer("GetPlayer")) {
            while (attempts < 4) {
                PlayerResponse response = grpcClientManager.getPlayerStub(3).getPlayer(PlayerRequest.newBuilder().setUuid(uuid.toString()).build());
                if (response.getErrorCode().isEmpty()) return response;

                String code = response.getErrorCode();

                if ("DATA_DIRTY".equals(code) || "ALREADY_LOADED".equals(code)) {
                    attempts++;
                    LOGGER.info("load() retry {}/4 for {} — reason: {}", attempts, uuid, code);
                    sleep(500);
                    continue;
                }

                LOGGER.error("load() failed for {} — code: {}, detail: {}", uuid, code, response.getErrorDetails());
                recordError("GetPlayer", code);
                return null;
            }

            LOGGER.error("load() exhausted 4 attempts for {}", uuid);
            return null;

        } catch (Exception e) {
            LOGGER.error(e, "load() network failure for {}", uuid);
            recordError("GetPlayer", e);
            return null;
        }
    }

    public CompletableFuture<Void> saveProfile(UUID uuid, Collection<Message> components) {
        if (components.isEmpty()) return CompletableFuture.completedFuture(null);

        SaveProfileRequest request = components.stream().reduce(SaveProfileRequest.newBuilder().setUuid(uuid.toString()),
                (b, c) -> b.addComponents(Any.pack(c)), (a, b) -> a).build();

        CompletableFuture<Void> future = new CompletableFuture<>();

        int count = components.size();
        submit(uuid, new RetryTask(uuid) {
            @Override
            public boolean execute() {
                try (var t = startTimer("SaveProfile")) {
                    var stub = grpcClientManager.getPlayerStub(4);
                    StandardResponse res = stub.saveProfile(request);

                    if (!res.getSuccess()) LOGGER.warn("saveProfile rejected for {}: {}", uuid, res.getMessage());
                    else {
                        future.complete(null);
                    }
                    return res.getSuccess();
                } catch (Exception e) {
                    future.completeExceptionally(e);
                    recordError("SaveProfile", e);
                    return false;
                }
            }

            @Override
            public String describe() {
                return "SaveProfile(" + count + " components)";
            }
        });
        return future;
    }

    public <T extends Message> CompletableFuture<Void> saveComponent(UUID uuid, T component) {
        Any packed = Any.pack(component);
        String typeUrl = typeUrlOf(component);
        CompletableFuture<Void> future = new CompletableFuture<>();


        submit(uuid, new RetryTask(uuid) {
            @Override
            public boolean execute() {
                try (var t = startTimer("SaveComponent")) {
                    StandardResponse res = grpcClientManager.getPlayerStub(3)
                            .saveComponent(SaveComponentRequest.newBuilder()
                                    .setUuid(uuid.toString())
                                    .setComponent(packed)
                                    .build());

                    if (!res.getSuccess()) {
                        LOGGER.warn("saveComponent rejected for {}: {}", uuid, res.getMessage());
                    } else future.complete(null);

                    return res.getSuccess();
                } catch (Exception e) {
                    future.completeExceptionally(e);
                    recordError("SaveComponent", e);
                    return false;
                }
            }

            @Override
            public String describe() {
                return "SaveComponent(" + typeUrl + ")";
            }
        });
        return future;
    }

    public <T extends Message> CompletableFuture<Void> deleteComponent(UUID uuid, T component) {
        String url = typeUrlOf(component);
        CompletableFuture<Void> future = new CompletableFuture<>();

        submit(uuid, new RetryTask(uuid) {
            @Override
            public boolean execute() {
                try (var t = startTimer("DeleteComponent")) {
                    StandardResponse res = grpcClientManager.getPlayerStub(3)
                            .deleteComponent(DeleteComponentRequest.newBuilder()
                                    .setUuid(uuid.toString())
                                    .setComponentUrl(url)
                                    .build());

                    if (!res.getSuccess()) {
                        LOGGER.warn("deleteComponent rejected for {}: {}", uuid, res.getMessage());
                    } else future.complete(null);
                    return res.getSuccess();
                } catch (Exception e) {
                    future.completeExceptionally(e);
                    recordError("DeleteComponent", e);
                    return false;
                }
            }

            @Override
            public String describe() {
                return "DeleteComponent(" + url + ")";
            }
        });
        return future;
    }

    public void flushQueueForPlayer(UUID uuid) {
        RetryQueue queue = retryQueues.get(uuid);
        if (queue == null) return;
        queue.flush(this::writeToDeadLetterFile);
        if (queue.isEmpty()) retryQueues.remove(uuid);
    }

    public void shutdown() {
        retryScheduler.shutdown();
        LOGGER.info("Shutdown: flushing all pending queues...");

        retryQueues.forEach((uuid, queue) -> queue.flush(this::writeToDeadLetterFile));

        retryQueues.forEach((uuid, queue) -> {
            if (!queue.isEmpty()) {
                LOGGER.error("[SHUTDOWN] {} task(s) could not be flushed for {} — writing to dead-letter", queue.size(), uuid);
                queue.drainToDeadLetter(this::writeToDeadLetterFile);
            }
        });

        retryQueues.clear();
        LOGGER.info("Shutdown complete.");
    }

    private void submit(UUID uuid, RetryTask task) {
        retryQueues.computeIfAbsent(uuid, RetryQueue::new).submit(task);
    }

    private void processAllRetries() {
        int total = 0;
        for (UUID uuid : retryQueues.keySet()) {
            flushQueueForPlayer(uuid);
            RetryQueue q = retryQueues.get(uuid);
            if (q != null) total += q.size();
        }
        if (tachyonMetrics != null) tachyonMetrics.updateRetryQueueSize(total);
    }

    private void writeToDeadLetterFile(RetryTask task) {
        LOGGER.error("[FATAL] Task exhausted for {}: {}", task.getUuid(), task.describe());
        try (var out = new PrintWriter(new FileWriter(new File(dataFolder, "recovery.log"), true))) {
            out.printf("[%s] UUID: %s | %s%n", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()), task.getUuid(), task.describe());
        } catch (IOException e) {
            LOGGER.error(e, "[FATAL] Cannot write to recovery.log!");
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            LOGGER.error(e, "Unable to sleep thread");
            Thread.currentThread().interrupt();
        }
    }

    private static String typeUrlOf(Message m) {
        return "type.googleapis.com/" + m.getDescriptorForType().getFullName();
    }

}
