package tech.skworks.tachyon.api.event;

import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * Contract for the Tachyon Event Bus.
 * <p>
 * Handles the registration, unregistration, and asynchronous dispatching of events.
 * Designed to be accessed via the {@link tech.skworks.tachyon.api.TachyonAPI}.
 * </p>
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @author Jimmy (vSKAH) - 20/05/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public interface EventBus<O> {

    <T extends TachyonEvent> void register(@NotNull O owner, @NotNull Class<T> eventClass, @NotNull Consumer<T> handler);

    <T extends TachyonEvent> void unregister(@NotNull O owner, @NotNull Class<T> eventClass, @NotNull Consumer<T> handler);

    void unregisterAll(@NotNull O owner);

    <T extends TachyonEvent> void post(@NotNull T event);
}