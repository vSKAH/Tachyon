package tech.skworks.tachyon.plugin.internal.retry;

import org.bukkit.Bukkit;
import tech.skworks.tachyon.plugin.spigot.TachyonCore;
import tech.skworks.tachyon.plugin.internal.util.TachyonLogger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Project Tachyon
 * Class RetryQueue
 *
 * @author  Jimmy (vSKAH) - 09/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public final class RetryQueue {

    private static final TachyonLogger LOGGER = TachyonCore.getModuleLogger("RetryQueue");

    private final UUID uuid;
    private final Deque<RetryTask> queue = new ArrayDeque<>();
    private boolean isProcessing = false;

    public RetryQueue(UUID uuid) {
        this.uuid = uuid;
    }

    public void submit(RetryTask task, Consumer<RetryTask> onExhausted) {
        synchronized (this) {
            queue.add(task);
            if (queue.size() > 1) {
                LOGGER.info("Task queued for {} ({} ahead): {}", uuid, queue.size() - 1, task.describe());
            }
        }
        if (Bukkit.isPrimaryThread()) {
            LOGGER.warn("submit() called from Main Thread for {}, offloading to Virtual Thread...", uuid);
            String shortId = uuid.toString().substring(0, 18);
            Thread.ofVirtual().name("tachyon-queue-" + shortId + "-", 1).start(() -> process(onExhausted));
        } else {
            process(onExhausted);
        }
    }

    public void process(Consumer<RetryTask> onExhausted) {
        if (Bukkit.isPrimaryThread()) {
            LOGGER.error("CRITICAL: 'process' reached inside the Bukkit Main Thread! Aborting execution.");
            return;
        }

        synchronized (this) {
            if (isProcessing || queue.isEmpty()) return;
            isProcessing = true;
        }

        try {
            while (true) {
                RetryTask task;
                synchronized (this) {
                    task = queue.peek();
                    if (task == null) return;
                    task.incrementAttempts();
                }

                boolean success = task.execute();

                synchronized (this) {
                    if (success) {
                        queue.poll();
                        LOGGER.info("Task executed successfully for {}: {}", uuid, task.describe());
                    } else {
                        LOGGER.warn("Task execution failed for {} (attempt {}/50): {}", uuid, task.getAttempts(), task.describe());

                        if (task.isExhausted()) {
                            LOGGER.error("[FATAL] Task exhausted for {}: {}", uuid, task.describe());
                            task.onExhausted();
                            onExhausted.accept(task);
                            queue.poll();
                        } else {
                            return;
                        }
                    }
                }
            }
        } finally {
            synchronized (this) {
                isProcessing = false;
            }
        }
    }

    public void drainToDeadLetter(Consumer<RetryTask> onExhausted) {
        synchronized (this) {
            int count = queue.size();
            if (count > 0) {
                LOGGER.error("Draining {} undeliverable task(s) to dead-letter for {}", count, uuid);
                while (!queue.isEmpty()) {
                    RetryTask task = queue.poll();
                    task.onExhausted();
                    onExhausted.accept(task);
                }
            }
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return queue.isEmpty();
        }
    }

    public int size() {
        synchronized (this) {
            return queue.size();
        }
    }
}
