package tech.skworks.tachyon.api.profile;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tech.skworks.tachyon.libs.com.google.protobuf.Message;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Project Tachyon
 * Class TachyonProfile
 *
 * <p> Component lifecycle: </p>
 * <ul>
 * <li> {@code setComponent()}     called by plugins — sets value AND marks dirty.</li>
 * <li> {@code saveComponent()}    marks dirty + immediately enqueues a gRPC save.</li>
 * <li> {@code saveProfile()}      flushes only dirty components to the backend.</li>
 * </ul>
 *
 * <p> The dirty set acts as a safety net: if a {@code saveComponent()} gRPC call hasn't
 * been confirmed yet when the player disconnects, {@code saveProfile()} will catch it. </p>
 *
 * @author  Jimmy (vSKAH) - 09/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public interface TachyonProfile {

    /**
     * Overwrites the current instance of the component in the profile memory
     * and marks it as dirty for the next profile save.
     *
     * @param component The new Protobuf component instance to store.
     * @param <T>       The specific type of the Protobuf message.
     */
    <T extends Message> void setComponent(@NotNull final T component);

    /**
     * Convenience method to safely update an existing component using its Builder.
     * <p>
     * This retrieves the current component, converts it to a Builder, applies your modifications,
     * rebuilds it, and automatically calls {@link #setComponent(Message)} to mark it as dirty.
     *
     * @param clazz    The class of the component to update.
     * @param modifier A consumer applying the changes to the component's builder.
     * @param <T>      The specific type of the Protobuf message.
     * @param <B>      The specific type of the Protobuf message's Builder.
     */
    <T extends Message, B extends Message.Builder> void updateComponent(@NotNull final Class<T> clazz, @NotNull final Consumer<B> modifier);

    /**
     * Retrieves a component by its class type, returning a fallback value if it is not found.
     *
     * @param clazz        The class of the component to retrieve.
     * @param defaultValue The value to return if the player does not have this component loaded.
     * @param <T>          The specific type of the Protobuf message.
     * @return The current component instance, or the provided default value.
     */
    <T extends Message> T getComponent(@NotNull final Class<T> clazz, @NotNull final T defaultValue);

    /**
     * Retrieves a component by its class type.
     *
     * @param clazz The class of the component to retrieve.
     * @param <T>   The specific type of the Protobuf message.
     * @return The current component instance, or {@code null} if not found.
     */
    @Nullable <T extends Message> T getComponent(@NotNull final Class<T> clazz);

    /**
     * Retrieves a component by its short name (e.g., "CookieComponent").
     *
     * @param componentShortName The exact short name of the Protobuf descriptor.
     * @param <T>                The specific type of the Protobuf message.
     * @return The current component instance, or {@code null} if not found.
     */
    @Nullable <T extends Message> T getComponent(@NotNull final String componentShortName);

    /**
     * Marks the given component as dirty and immediately enqueues an asynchronous
     * save operation to the backend via gRPC.
     *
     * @param component The component to save immediately.
     * @param <T>       The specific type of the Protobuf message.
     * @return A CompletableFuture that completes when the backend acknowledges the save.
     */
    <T extends Message> CompletableFuture<Void> saveComponent(@NotNull final T component);

    /**
     * Resets or deletes a component from the profile and immediately enqueues an asynchronous
     * delete operation to the backend via gRPC.
     *
     * @param component The component to remove immediately.
     * @param <T>          The specific type of the Protobuf message.
     * @return A CompletableFuture that completes when the backend acknowledges the deletion.
     */
    <T extends Message> CompletableFuture<Void> deleteComponent(@NotNull final T component);

    /**
     * Asynchronously flushes all components currently marked as "dirty" to the backend.
     * <p>
     * This is typically called during player disconnection or periodic auto-saves
     * to ensure no data is lost.
     *
     * @return A CompletableFuture that completes when all dirty components are saved.
     */
    CompletableFuture<Void> saveProfile();

    /**
     * Gets the unique identifier of the player who owns this profile.
     *
     * @return The player's UUID.
     */
    UUID getUuid();

}
