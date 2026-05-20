package tech.skworks.tachyon.plugin.core.event;

import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import tech.skworks.tachyon.api.event.EventBus;
import tech.skworks.tachyon.api.event.TachyonEvent;
import tech.skworks.tachyon.plugin.common.util.TachyonLogger;
import tech.skworks.tachyon.plugin.spigot.TachyonCore;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Core implementation of the Tachyon Event Bus.
 * <p>
 * Uses lock-free collections for instantaneous read operations and a {@link java.util.concurrent.ForkJoinPool}
 * for high-performance, work-stealing asynchronous dispatching. Listeners are bound to their respective
 * {@link JavaPlugin} to allow seamless unregistration upon plugin disable.
 * </p>
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @author Jimmy (vSKAH) - 20/05/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class TachyonEventBusImpl implements EventBus<JavaPlugin> {

    private static final TachyonLogger LOGGER = TachyonCore.getModuleLogger("Tachyon-Event");
    private final Map<Class<? extends TachyonEvent>, List<RegisteredListener<?>>> listeners = new ConcurrentHashMap<>();
    private final ExecutorService asyncPool;

    public TachyonEventBusImpl() {
        int targetParallelism = Math.clamp(Runtime.getRuntime().availableProcessors(), 2, 6);

        ForkJoinPool.ForkJoinWorkerThreadFactory factory = pool -> {
            ForkJoinWorkerThread worker = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
            if (worker != null) {
                worker.setName("Tachyon-Event-" + worker.getPoolIndex());
            }
            return worker;
        };

        this.asyncPool = new ForkJoinPool(
                targetParallelism,
                factory,
                (t, e) -> LOGGER.error(e, "Tachyon Async Exception on thread {}", t.getName()),
                true);
    }

    @Override
    public <T extends TachyonEvent> void register(@NotNull JavaPlugin owner, @NotNull Class<T> eventClass, @NotNull Consumer<T> handler) {
        listeners.computeIfAbsent(eventClass, k -> new CopyOnWriteArrayList<>()).add(new RegisteredListener<>(owner, handler));
    }

    @Override
    public <T extends TachyonEvent> void unregister(@NotNull JavaPlugin owner, @NotNull Class<T> eventClass, @NotNull Consumer<T> handler) {
        List<RegisteredListener<?>> eventListeners = listeners.get(eventClass);
        if (eventListeners != null) {
            eventListeners.removeIf(rl -> rl.owns(owner) && rl.isHandler(handler));
        }
    }

    @Override
    public void unregisterAll(@NotNull JavaPlugin owner) {
        for (List<RegisteredListener<?>> list : listeners.values()) {
            list.removeIf(registeredListener -> registeredListener.owns(owner));
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends TachyonEvent> void post(@NotNull T event) {
        List<RegisteredListener<?>> eventListeners = listeners.get(event.getClass());

        if (eventListeners == null || eventListeners.isEmpty()) {
            return;
        }

        asyncPool.execute(() -> {
            for (RegisteredListener<?> rl : eventListeners) {
                try {
                    ((RegisteredListener<T>) rl).handler().accept(event);
                } catch (Exception e) {
                    LOGGER.error(e, "Error dispatching Tachyon event {}", event.getClass().getSimpleName());
                }
            }
        });
    }

    /**
     * Cleanly shuts down the event bus and its underlying thread pool.
     * <p>
     * Should only be called by the Tachyon core plugin during the onDisable phase
     * to prevent hanging async threads upon server shutdown.
     * </p>
     */
    public void shutdown() {
        asyncPool.shutdown();
        try {
            if (!asyncPool.awaitTermination(5, TimeUnit.SECONDS)) {
                asyncPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            asyncPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        listeners.clear();
    }
}