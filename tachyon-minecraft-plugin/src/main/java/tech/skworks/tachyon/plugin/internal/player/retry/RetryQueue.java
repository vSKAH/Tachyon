package tech.skworks.tachyon.plugin.internal.player.retry;

import tech.skworks.tachyon.plugin.TachyonCore;
import tech.skworks.tachyon.plugin.internal.util.TachyonLogger;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
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
    private final ConcurrentLinkedQueue<RetryTask> queue = new ConcurrentLinkedQueue<>();
    private final Object lock = new Object();

    public RetryQueue(UUID uuid) {
        this.uuid = uuid;
    }

    /**
     * Submits a task for execution.
     *
     * If the queue is empty, the task is attempted immediately (outside the lock).
     * If the task fails or if another task is already queued, it is enqueued for retry.
     *
     */
    public void submit(RetryTask task) {
        synchronized (lock) {
            if (!queue.isEmpty()) {
                queue.add(task);
                LOGGER.info("Task queued for {} (preserving order, {} ahead): {}", uuid, queue.size() - 1, task.describe());
                return;
            }
            queue.add(task);
        }

        boolean success = task.execute();

        synchronized (lock) {
            if (success) {
                if (queue.peek() == task) {
                    queue.poll();
                    LOGGER.info("Task executed immediately for {}: {}", uuid, task.describe());
                }
            } else {
                LOGGER.warn("Initial execution failed for {}, queued for retry: {}", uuid, task.describe());
            }
        }
    }

    /**
     * Drains the queue, replaying tasks in FIFO order.
     * Stops at the first failure to preserve ordering semantics.
     *
     * @param onExhausted called for permanently failed tasks (dead-letter)
     */
    public void flush(Consumer<RetryTask> onExhausted) {
        if (queue.isEmpty()) return;

        LOGGER.info("Flushing {} pending task(s) for {}", queue.size(), uuid);

        while (true) {
            RetryTask task;

            synchronized (lock) {
                task = queue.peek();
                if (task == null) break;
                task.incrementAttempts();
            }

            boolean success = task.execute();

            synchronized (lock) {
                if (success) {
                    queue.poll();
                    LOGGER.info("Retry succeeded for {} (attempt {}): {}", uuid, task.getAttempts(), task.describe());
                } else {
                    LOGGER.warn("Retry attempt {}/{} failed for {}: {}", task.getAttempts(), 50, uuid, task.describe());

                    if (task.isExhausted()) {
                        LOGGER.error("[FATAL] Task permanently exhausted for {} after {} attempts: {}", uuid, task.getAttempts(), task.describe());
                        onExhausted.accept(task);
                        queue.poll();
                    } else {
                        break;
                    }
                }
            }
        }
    }

    /**
     * Drains all remaining tasks to the dead-letter handler without retrying.
     * Called only during shutdown when the service is about to stop.
     */
    public void drainToDeadLetter(Consumer<RetryTask> onExhausted) {
        synchronized (lock) {
            int count = queue.size();
            if (count > 0) {
                LOGGER.error("Draining {} undeliverable task(s) to dead-letter for {}", count, uuid);
            }
            while (!queue.isEmpty()) {
                RetryTask task = queue.poll();
                if (task != null) onExhausted.accept(task);
            }
        }
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    public int size() {
        return queue.size();
    }

    public UUID getUuid() {
        return uuid;
    }
}
