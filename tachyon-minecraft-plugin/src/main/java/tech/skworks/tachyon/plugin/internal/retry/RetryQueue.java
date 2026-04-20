package tech.skworks.tachyon.plugin.internal.retry;

import org.bukkit.Bukkit;
import tech.skworks.tachyon.plugin.spigot.TachyonCore;
import tech.skworks.tachyon.plugin.internal.util.TachyonLogger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
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
    private boolean isProcessing = false;

    public RetryQueue(UUID uuid) {
        this.uuid = uuid;
    }

    public void submit(RetryTask task, Consumer<RetryTask> onExhausted) {
        lock.lock();
        try {
            queue.add(task);
            if (queue.size() > 1) {
                LOGGER.info("Task queued for {} ({} ahead): {}", uuid, queue.size() - 1, task.describe());
            }
        } finally {
            lock.unlock();
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

        lock.lock();
        try {
            if (isProcessing || queue.isEmpty()) return;
            isProcessing = true;
        } finally {
            lock.unlock();
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

                // L'exécution se fait HORS verrou pour ne pas bloquer les autres soumissions
                boolean success = task.execute();

                lock.lock();
                try {
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
                            // On arrête le traitement de cette queue pour ce cycle en cas d'échec non épuisé
                            return;
                        }
                    }

                    if (queue.isEmpty()) return;
                } finally {
                    lock.unlock();
                }
            }
        } finally {
            lock.lock();
            try {
                isProcessing = false;
            } finally {
                lock.unlock();
            }
        }
    }

    public void drainToDeadLetter(Consumer<RetryTask> onExhausted) {
        lock.lock();
        try {
            int count = queue.size();
            if (count > 0) {
                LOGGER.error("Draining {} undeliverable task(s) to dead-letter for {}", count, uuid);
                while (!queue.isEmpty()) {
                    RetryTask task = queue.poll();
                    if (task != null) {
                        task.onExhausted();
                        onExhausted.accept(task);
                    }
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
