package tech.skworks.tachyon.api.profile;

import org.bson.BsonDocument;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tech.skworks.tachyon.api.component.ComponentNamespace;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

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
    <T extends Record> void setComponent(@NotNull final ComponentNamespace componentNamespace, @NotNull final T component);

    /**
     * Convenience method to safely update an existing Record component using its Builder.
     * <p>
     * This retrieves the current immutable Record component, applies the modification function
     * replaces the stored component, and automatically mark it as dirty for the next save cycle, and increment its version counter.
     *
     * @param clazz    The class of the Record component to update
     * @param modifier  A function taking the current Record instance and returning a new updated Record instance.
     * @param <T>      The specific record comportnent type
     * @deprecated Use {@link #updateComponent(ComponentNamespace, Class, Supplier, UnaryOperator)} or {@link #computeComponent(ComponentNamespace, Class, Function)} instead.
     */
    @Deprecated(since = "2.0.0-alpha.2")
    <T extends Record> void updateComponent(@NotNull final ComponentNamespace componentNamespace, @NotNull final Class<T> clazz, @NotNull final UnaryOperator<T> modifier);


    /**
     * Atomically updates an existing Record component or initializes it using the provided fallback supplier.
     * <p>
     * If the component is present, {@code modifier} is applied to the current instance.
     * If absent, {@code defaultSupplier} is lazily evaluated to provide an initial base state before applying {@code modifier}.
     * Marks the component as dirty and increments its version counter.
     * </p>
     *
     * @param namespace       The namespace of the component to update.
     * @param clazz           The class of the Record component.
     * @param defaultSupplier Lazy supplier invoked only if the component is absent (zero GC allocation if present).
     * @param modifier        Function to apply to the current or base component.
     * @param <T>             The specific Record component type.
     * @return The newly stored Record instance.
     */
    <T extends Record> T updateComponent(@NotNull final ComponentNamespace namespace, @NotNull final Class<T> clazz, @NotNull final Supplier<T> defaultSupplier, @NotNull final UnaryOperator<T> modifier);

    /**
     * Atomically computes a new Record component instance based on its current state.
     * <p>
     * The remapping function receives the current instance, or {@code null} if absent.
     * Marks the component as dirty and increments its version counter.
     * </p>
     *
     * @param namespace The namespace of the component to compute.
     * @param clazz     The class of the Record component.
     * @param mapper    Remapping function taking current nullable instance and returning new non-null instance.
     * @param <T>       The specific Record component type.
     * @return The newly stored Record instance.
     */
    <T extends Record> T computeComponent(@NotNull final ComponentNamespace namespace, @NotNull final Class<T> clazz, @NotNull final Function<@Nullable T, @NotNull T> mapper);

        /**
         * Retrieves a component by its class type, returning a fallback value if it is not found.
         *
         * @param componentNamespace        The class of the component to retrieve.
         * @param defaultValue The value to return if the player does not have this component loaded.
         * @param <T>          The specific type of the Protobuf message.
         * @return The current component instance, or the provided default value.
         */
    <T extends Record> T getComponent(@NotNull final ComponentNamespace componentNamespace, @NotNull final T defaultValue);

    /**
     * Retrieves a component by its class type.
     *
     * @param componentNamespace The class of the component to retrieve.
     * @param <T>   The specific type of the Protobuf message.
     * @return The current component instance, or {@code null} if not found.
     */
    @Nullable <T extends Record> T getComponent(@NotNull final ComponentNamespace componentNamespace);


    void removeComponent(@NotNull final ComponentNamespace componentNamespace);

    boolean hasPendingChanges();

    @NotNull Map<ComponentNamespace, BsonDocument> extractDirtyComponents();

    @NotNull List<ComponentNamespace> extractDeletedComponents();

    void markAsClean(@NotNull final Map<ComponentNamespace, Long> savedClasses, @NotNull final Collection<ComponentNamespace> deletedComponent);
    /**
     * Gets the unique identifier of the player who owns this profile.
     *
     * @return The player's UUID.
     */
    UUID getUuid();

    Long getVersion(@NotNull final ComponentNamespace namespace);

    /**
     * Freezes the profile to prevent any subsequent mutation.
     * Useful when evicting or unloading a profile to protect against delayed async tasks.
     */
    void freeze();

    /**
     * Returns whether this profile is frozen.
     *
     * @return true if the profile is frozen, false otherwise.
     */
    boolean isFrozen();
}
