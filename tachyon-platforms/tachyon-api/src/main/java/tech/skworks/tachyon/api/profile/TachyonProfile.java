package tech.skworks.tachyon.api.profile;

import org.bson.BsonDocument;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tech.skworks.tachyon.api.component.ComponentKey;
import tech.skworks.tachyon.api.component.ComponentNamespace;
import tech.skworks.tachyon.api.component.ComponentRegistry;

import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * High-performance, concurrent player profile state container.
 * <p>
 * Under Java 25 and Folia 26.3, TachyonProfile is 100% lock-free, utilizing
 * atomic reference arrays and dense slot indexation via {@link ComponentKey}.
 * Historical methods based on {@link ComponentNamespace} are maintained as default
 * methods for complete binary backward compatibility.
 * </p>
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @author Jimmy (vSKAH) - 09/04/2026
 * @version 2.0
 * @since 1.0.0-SNAPSHOT
 */
public interface TachyonProfile {

    /**
     * Returns the ComponentRegistry associated with this profile runtime.
     *
     * @return The ComponentRegistry instance.
     */
    @NotNull ComponentRegistry getRegistry();

    /**
     * Gets the unique identifier of the player who owns this profile.
     *
     * @return The player's UUID.
     */
    @NotNull UUID getUuid();

    // =========================================================================
    // PRIMARY HOT-PATH API: O(1) PURE, LOCK-FREE VIA COMPONENTKEY<T>
    // =========================================================================

    /**
     * Retrieves a component using its typed ComponentKey in O(1) time (~2.8ns).
     *
     * @param key The typed component key.
     * @param <T> The record component type.
     * @return The current component instance, or null if absent.
     */
    <T extends Record> @Nullable T getComponent(@NotNull ComponentKey<T> key);

    /**
     * Retrieves a component using its typed ComponentKey, returning a fallback value if absent.
     *
     * @param key          The typed component key.
     * @param defaultValue Fallback value if absent.
     * @param <T>          The record component type.
     * @return The component or defaultValue.
     */
    default <T extends Record> @NotNull T getComponent(@NotNull ComponentKey<T> key, @NotNull T defaultValue) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(defaultValue, "defaultValue cannot be null");
        final T value = getComponent(key);
        return value != null ? value : defaultValue;
    }

    /**
     * Retrieves a component using its typed ComponentKey, falling back to defaultSupplier if defined.
     *
     * @param key The typed component key.
     * @param <T> The record component type.
     * @return The component instance.
     * @throws IllegalStateException If component is absent and key has no defaultSupplier.
     */
    default <T extends Record> @NotNull T getComponentOrDefault(@NotNull ComponentKey<T> key) {
        Objects.requireNonNull(key, "key cannot be null");
        final T value = getComponent(key);
        if (value != null) return value;
        if (key.defaultSupplier() != null) return key.defaultSupplier().get();
        throw new IllegalStateException("Component is absent and no default supplier defined for: " + key.namespace());
    }

    /**
     * Atomically mutates a component using its typed ComponentKey and modifier function.
     *
     * @param key      The typed component key.
     * @param modifier Transformation function applied to current or default state.
     * @param <T>      The record component type.
     * @return The updated component record instance.
     */
    default <T extends Record> @NotNull T updateComponent(@NotNull ComponentKey<T> key, @NotNull UnaryOperator<T> modifier) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(modifier, "modifier cannot be null");
        return computeComponent(key, current -> modifier.apply(current != null ? current : (key.defaultSupplier() != null ? key.defaultSupplier().get() : null)));
    }

    /**
     * Stores or overwrites a component using its typed ComponentKey.
     *
     * @param key       The typed component key.
     * @param component The new component record instance.
     * @param <T>       The record component type.
     */
    <T extends Record> void setComponent(@NotNull ComponentKey<T> key, @NotNull T component);

    /**
     * Atomically computes a new component value using its typed ComponentKey.
     *
     * @param key      The typed component key.
     * @param remapper Function taking existing component (or null) and returning new non-null component.
     * @param <T>      The record component type.
     * @return The updated component record instance.
     */
    <T extends Record> T computeComponent(@NotNull ComponentKey<T> key, @NotNull Function<@Nullable T, @NotNull T> remapper);

    /**
     * Removes a component by marking its slot as a tombstone.
     *
     * @param key The typed component key to remove.
     */
    void removeComponent(@NotNull ComponentKey<?> key);

    /**
     * Returns true if any slot has been modified or deleted since the last confirmed save.
     *
     * @return true if there are pending unpersisted changes.
     */
    boolean hasPendingChanges();

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

    /**
     * Retrieves the monotonically increasing version counter for a component namespace.
     *
     * @param namespace The component namespace.
     * @return The version number, or null if unversioned.
     */
    Long getVersion(@NotNull ComponentNamespace namespace);

    // =========================================================================
    // RETROCOMPATIBLE HISTORICAL API (DEFAULT METHODS DELEGATING TO COMPONENTKEY)
    // =========================================================================

    /**
     * Retrieves a component by its namespace.
     *
     * @param componentNamespace The namespace of the component.
     * @param <T>                The record component type.
     * @return The component or null.
     * @deprecated Hot-path warning: Resolves ComponentNamespace dynamically (~35ns).
     *             Use {@link #getComponent(ComponentKey)} (~2.8ns) for ticking game loops.
     */
    @Deprecated(since = "2.0.0", forRemoval = false)
    @SuppressWarnings("unchecked")
    default @Nullable <T extends Record> T getComponent(@NotNull final ComponentNamespace componentNamespace) {
        Objects.requireNonNull(componentNamespace, "componentNamespace cannot be null");
        final ComponentKey<?> key = getRegistry().getKey(componentNamespace);
        if (key == null) return null;
        return (T) getComponent(key);
    }

    /**
     * Retrieves a component by its namespace, returning a fallback value if not found.
     *
     * @param componentNamespace The namespace of the component.
     * @param defaultValue       The fallback value.
     * @param <T>                The record component type.
     * @return The current component instance, or defaultValue.
     * @deprecated Hot-path warning: Use {@link #getComponent(ComponentKey)}.
     */
    @Deprecated(since = "2.0.0", forRemoval = false)
    @SuppressWarnings("unchecked")
    default <T extends Record> T getComponent(@NotNull final ComponentNamespace componentNamespace, @NotNull final T defaultValue) {
        Objects.requireNonNull(componentNamespace, "componentNamespace cannot be null");
        Objects.requireNonNull(defaultValue, "defaultValue cannot be null");
        final ComponentKey<?> key = getRegistry().getKey(componentNamespace);
        if (key == null) return defaultValue;
        final T value = (T) getComponent(key);
        return value != null ? value : defaultValue;
    }

    /**
     * Overwrites the component in profile memory and marks it dirty.
     *
     * @param componentNamespace The namespace of the component.
     * @param component          The component record instance.
     * @param <T>                The record component type.
     * @deprecated Hot-path warning: Use {@link #setComponent(ComponentKey, Record)}.
     */
    @Deprecated(since = "2.0.0", forRemoval = false)
    @SuppressWarnings("unchecked")
    default <T extends Record> void setComponent(@NotNull final ComponentNamespace componentNamespace, @NotNull final T component) {
        Objects.requireNonNull(componentNamespace, "componentNamespace cannot be null");
        Objects.requireNonNull(component, "component cannot be null");
        final ComponentKey<T> key = (ComponentKey<T>) getRegistry().getOrCreateKey(componentNamespace, (Class<T>) component.getClass(), null);
        setComponent(key, component);
    }

    /**
     * Safely updates an existing Record component if present.
     *
     * @param componentNamespace The namespace of the component.
     * @param clazz              The component record class.
     * @param modifier           The mutation operator.
     * @param <T>                The record component type.
     * @deprecated Use {@link #updateComponent(ComponentNamespace, Class, Supplier, UnaryOperator)} or {@link ComponentKey#update}.
     */
    @Deprecated(since = "2.0.0-alpha.2", forRemoval = false)
    default <T extends Record> void updateComponent(@NotNull final ComponentNamespace componentNamespace,
                                                   @NotNull final Class<T> clazz,
                                                   @NotNull final UnaryOperator<T> modifier) {
        Objects.requireNonNull(componentNamespace, "componentNamespace cannot be null");
        Objects.requireNonNull(clazz, "clazz cannot be null");
        Objects.requireNonNull(modifier, "modifier cannot be null");
        final ComponentKey<?> rawKey = getRegistry().getKey(componentNamespace);
        if (rawKey == null) return;
        @SuppressWarnings("unchecked")
        final ComponentKey<T> key = (ComponentKey<T>) rawKey;
        final T current = getComponent(key);
        if (current != null) {
            computeComponent(key, c -> c != null ? modifier.apply(c) : current);
        }
    }

    /**
     * Atomically updates an existing Record component or initializes it using fallback supplier.
     *
     * @param namespace       The namespace of the component.
     * @param clazz           The component record class.
     * @param defaultSupplier Lazy supplier for initial state if absent.
     * @param modifier        The mutation operator.
     * @param <T>             The record component type.
     * @return The updated component record instance.
     * @deprecated Hot-path warning: Use {@link ComponentKey#update(TachyonProfile, UnaryOperator)}.
     */
    @Deprecated(since = "2.0.0", forRemoval = false)
    default <T extends Record> T updateComponent(@NotNull final ComponentNamespace namespace,
                                                @NotNull final Class<T> clazz,
                                                @NotNull final Supplier<T> defaultSupplier,
                                                @NotNull final UnaryOperator<T> modifier) {
        return computeComponent(namespace, clazz, current -> modifier.apply(current != null ? current : defaultSupplier.get()));
    }

    /**
     * Atomically computes a new Record component instance based on its current state.
     *
     * @param namespace The namespace of the component.
     * @param clazz     The component record class.
     * @param mapper    Remapping function.
     * @param <T>       The record component type.
     * @return The updated component record instance.
     * @deprecated Hot-path warning: Use {@link #computeComponent(ComponentKey, Function)}.
     */
    @Deprecated(since = "2.0.0", forRemoval = false)
    default <T extends Record> T computeComponent(@NotNull final ComponentNamespace namespace,
                                                 @NotNull final Class<T> clazz,
                                                 @NotNull final Function<@Nullable T, @NotNull T> mapper) {
        Objects.requireNonNull(namespace, "namespace cannot be null");
        Objects.requireNonNull(clazz, "clazz cannot be null");
        Objects.requireNonNull(mapper, "mapper cannot be null");
        final ComponentKey<T> key = getRegistry().getOrCreateKey(namespace, clazz, null);
        return computeComponent(key, mapper);
    }

    /**
     * Removes a component by its namespace.
     *
     * @param componentNamespace The namespace of the component.
     * @deprecated Use {@link #removeComponent(ComponentKey)}.
     */
    @Deprecated(since = "2.0.0", forRemoval = false)
    default void removeComponent(@NotNull final ComponentNamespace componentNamespace) {
        Objects.requireNonNull(componentNamespace, "componentNamespace cannot be null");
        final ComponentKey<?> key = getRegistry().getKey(componentNamespace);
        if (key != null) {
            removeComponent(key);
        }
    }

    // =========================================================================
    // DEPRECATED EXTRACTION METHODS (KEPT FOR BINARY ABI BACKWARD COMPATIBILITY)
    // =========================================================================

    @Deprecated(since = "2.0.0", forRemoval = false)
    default @NotNull Map<ComponentNamespace, BsonDocument> extractDirtyComponents() {
        return Collections.emptyMap();
    }

    @Deprecated(since = "2.0.0", forRemoval = false)
    default @NotNull List<ComponentNamespace> extractDeletedComponents() {
        return Collections.emptyList();
    }

    @Deprecated(since = "2.0.0", forRemoval = false)
    default void markAsClean(@NotNull final Map<ComponentNamespace, Long> savedClasses, @NotNull final Collection<ComponentNamespace> deletedComponent) {
    }
}
