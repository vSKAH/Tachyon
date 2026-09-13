package tech.skworks.tachyon.core.event;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.skworks.tachyon.api.event.EventBus;
import tech.skworks.tachyon.api.event.TachyonEvent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Core implementation of the Tachyon Event Bus.
 * <p>
 * Uses lock-free collections for instantaneous read operations and virtual threads
 * for high-performance. Listeners are bound to their respective
 * {@link PLUGIN} to allow seamless unregistration upon plugin disable.
 * </p>
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @author Jimmy (vSKAH) - 20/05/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class TachyonEventBusImpl<PLUGIN> implements EventBus<PLUGIN> {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tachyon/EventBus");

    private final Map<Class<? extends TachyonEvent>,
            List<RegisteredListener<PLUGIN, ? extends TachyonEvent>>> listeners;
    private final ExecutorService asyncPool;

    public TachyonEventBusImpl() {
        this.listeners = new ConcurrentHashMap<>();

        this.asyncPool = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("tachyon-eventbus-", 1)
                .uncaughtExceptionHandler((t, e) -> LOGGER.error("Tachyon Async Exception on thread {}", t.getName(), e))
                .factory());
    }

    @Override
    public <T extends TachyonEvent> void register(@NotNull PLUGIN owner, @NotNull Class<T> eventClass, @NotNull Consumer<T> handler) {
        listeners.computeIfAbsent(eventClass, k -> new CopyOnWriteArrayList<>()).add(new RegisteredListener<>(owner, handler));
    }

    @Override
    public <T extends TachyonEvent> void unregister(@NotNull PLUGIN owner, @NotNull Class<T> eventClass, @NotNull Consumer<T> handler) {
        List<RegisteredListener<PLUGIN, ?>> eventListeners = listeners.get(eventClass);
        if (eventListeners != null) {
            eventListeners.removeIf(rl -> rl.owns(owner) && rl.isHandler(handler));
        }
    }

    @Override
    public void unregisterAll(@NotNull PLUGIN owner) {
        for (List<RegisteredListener<PLUGIN, ?>> list : listeners.values()) {
            list.removeIf(registeredListener -> registeredListener.owns(owner));
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends TachyonEvent> void post(@NotNull T event) {
        List<RegisteredListener<PLUGIN, ?>> eventListeners = listeners.get(event.getClass());

        if (eventListeners == null || eventListeners.isEmpty()) {
            return;
        }

        asyncPool.execute(() -> {
            for (final var registeredListener : eventListeners) {
                final var start = System.nanoTime();
                try {
                    ((RegisteredListener<PLUGIN, T>) registeredListener).handler().accept(event);
                } catch (Exception e) {
                    LOGGER.error("Plugin '{}' threw an exception in event {}", registeredListener.owner(), event.getClass().getSimpleName(), e);
                }
                finally {
                    final var elapsedMs = (System.nanoTime() - start) / 1_000_000;
                    if (elapsedMs > 35) {
                        LOGGER.warn("Plugin '{}' took {}ms to process event '{}' (Slow execution warning!)", registeredListener.owner(), elapsedMs, event.getClass().getSimpleName());
                    }
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