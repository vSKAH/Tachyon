package tech.skworks.tachyon.api.profile;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tech.skworks.tachyon.api.component.ComponentKey;
import tech.skworks.tachyon.api.component.ComponentRegistry;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * High-performance, concurrent player profile state container.
 * <p>
 * Under Java 25 and Folia 26.3, TachyonProfile is 100% lock-free, utilizing
 * atomic reference arrays and dense slot indexation via {@link ComponentKey}.
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
     * @return The current component instance, or null if absent or deleted.
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
     * Retrieves the monotonically increasing version counter for a component key in O(1).
     *
     * @param key The component key.
     * @return The current version revision number.
     */
    long getVersion(@NotNull ComponentKey<?> key);
}
