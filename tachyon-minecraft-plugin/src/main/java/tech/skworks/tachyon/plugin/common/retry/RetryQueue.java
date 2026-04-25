package tech.skworks.tachyon.plugin.common.retry;

import org.bukkit.Bukkit;
import tech.skworks.tachyon.plugin.spigot.TachyonCore;
import tech.skworks.tachyon.plugin.common.util.TachyonLogger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
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
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);

    public RetryQueue(UUID uuid) {
        this.uuid = uuid;
    }

    public void submit(RetryTask task) {
        lock.lock();
        try {
            boolean inserted = queue.offer(task);
            if (inserted) {
                LOGGER.info("Task queued for {} ({} ahead): {}", uuid, queue.size() - 1, task.describe());
            }
            else {
                LOGGER.error("Unable to enqueue task for {} (Queue full): {}", uuid, task.describe());
            }
        } finally {
            lock.unlock();
        }
    }

    public void process(Consumer<RetryTask> onExhausted) {
        if (Bukkit.isPrimaryThread()) {
            LOGGER.error("'process' reached inside the Bukkit Main Thread! Aborting execution.");
            return;
        }

        if (!isProcessing.compareAndSet(false, true)) {
            return;
        }

        try {
            while (true) {
                RetryTask task;

                lock.lock();
                try {
                    task = queue.peek();
                    if (task == null) return;
                    task.incrementAttempts();
                } finally {
                    lock.unlock();
                }

                boolean success = task.execute();

                lock.lock();
                try {
                    if (success) {
                        queue.poll();
                        LOGGER.info("Task executed successfully for {}: {}", uuid, task.describe());
                    }
                    else {
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

                    if (queue.isEmpty()) return;
                } finally {
                    lock.unlock();
                }
            }
        } finally {
            isProcessing.setRelease(false);
        }
    }

    public void flushToRecovery(Consumer<RetryTask> writeToRecoveryFile) {
        lock.lock();
        try {
            if (queue.isEmpty()) return;

            final int count = queue.size();
            LOGGER.error("Draining {} undeliverable task(s) to dead-letter for {}", count, uuid);

            while (!queue.isEmpty()) {
                RetryTask task = queue.poll();
                if (task != null) {
                    task.onExhausted();
                    writeToRecoveryFile.accept(task);
                }
            }

        } finally {
            lock.unlock();
        }
    }

    public boolean isEmpty() {
        lock.lock();
        try {
            return queue.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }
}
