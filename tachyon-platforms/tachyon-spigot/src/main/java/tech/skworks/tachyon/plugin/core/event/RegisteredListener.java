package tech.skworks.tachyon.plugin.core.event;

import org.bukkit.plugin.Plugin;
import tech.skworks.tachyon.api.event.TachyonEvent;

import java.util.function.Consumer;

/**
 * Internal record used to bind an event handler to its owning Plugin.
 * <p>
 * Guarantees immutability and zero-allocation overhead during event dispatching.
 * This record is used internally by the {@link TachyonEventBusImpl} to manage
 * listener lifecycles and safely unregister them upon plugin disable.
 * </p>
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @param <T>     The type of the event being listened to.
 * @param plugin  The plugin that registered and owns this listener.
 * @param handler The consumer responsible for executing the event logic.
 *
 * @author Jimmy (vSKAH) - 20/05/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
record RegisteredListener<T extends TachyonEvent>(Plugin plugin, Consumer<T> handler) {

    /**
     * Checks if this listener is owned by the specified plugin.
     *
     * @param targetPlugin The plugin to check against.
     * @return {@code true} if this listener belongs to the target plugin, {@code false} otherwise.
     */
    public boolean owns(Plugin targetPlugin) {
        return this.plugin.equals(targetPlugin);
    }

    /**
     * Checks if this listener wraps the specified handler instance.
     *
     * @param targetHandler The handler consumer to check against.
     * @return {@code true} if the handlers match exactly, {@code false} otherwise.
     */
    public boolean isHandler(Consumer<?> targetHandler) {
        return this.handler.equals(targetHandler);
    }
}