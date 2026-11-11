package tech.skworks.tachyon.api.component;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tech.skworks.tachyon.api.profile.TachyonProfile;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Strongly-typed, immutable token representing a persistent player data component.
 * <p>
 * Combines zero-indirection integer slot indexing with high-level developer ergonomics
 * and dual-symmetry access (profile-first and component-first).
 * </p>
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @param slotIndex       The dense, contiguous integer index (0 <= slotIndex < 128) allocated by ComponentRegistry.
 * @param namespace       The canonical namespace of the component.
 * @param type            The Java 25 Record class representing the component state.
 * @param defaultSupplier Optional supplier evaluated lazily when the component is absent.
 * @param <T>             The component record type.
 *
 * @author Jimmy (vSKAH) - 11/11/2026
 * @version 2.0
 * @since 2.0.0-SNAPSHOT
 */
public record ComponentKey<T extends Record>(
        int slotIndex,
        @NotNull ComponentNamespace namespace,
        @NotNull Class<T> type,
        @Nullable Supplier<T> defaultSupplier
) {
    public ComponentKey {
        Objects.requireNonNull(namespace, "namespace cannot be null");
        Objects.requireNonNull(type, "type cannot be null");
        if (slotIndex < 0 || slotIndex >= 128) {
            throw new IllegalArgumentException("slotIndex must be between 0 and 127. Got: " + slotIndex);
        }
    }

    /**
     * Retrieves the component from the given profile using this key.
     *
     * @param profile The player profile to query.
     * @return The current component instance, or null if absent and no default supplier exists.
     */
    public @Nullable T get(@NotNull final TachyonProfile profile) {
        Objects.requireNonNull(profile, "profile cannot be null");
        return profile.getComponent(this);
    }

    /**
     * Retrieves the component from the profile, falling back to defaultSupplier or throwing if null.
     *
     * @param profile The player profile to query.
     * @return The non-null component instance.
     * @throws IllegalStateException If component is absent and defaultSupplier is null.
     */
    public @NotNull T getOrDefault(@NotNull final TachyonProfile profile) {
        final T value = get(profile);
        if (value != null) return value;
        if (defaultSupplier != null) return defaultSupplier.get();
        throw new IllegalStateException("Component is absent and no default supplier defined for: " + namespace);
    }

    /**
     * Stores a new component instance into the profile.
     *
     * @param profile   The player profile to update.
     * @param component The new component state.
     */
    public void set(@NotNull final TachyonProfile profile, @NotNull final T component) {
        Objects.requireNonNull(profile, "profile cannot be null");
        profile.setComponent(this, component);
    }

    /**
     * Atomically mutates the component using the provided modifier.
     *
     * @param profile  The player profile to mutate.
     * @param modifier Transformation function applied to current or default state.
     * @return The newly stored component instance.
     */
    public @NotNull T update(@NotNull final TachyonProfile profile, @NotNull final UnaryOperator<T> modifier) {
        Objects.requireNonNull(profile, "profile cannot be null");
        Objects.requireNonNull(modifier, "modifier cannot be null");
        return profile.computeComponent(this, current -> modifier.apply(current != null ? current : (defaultSupplier != null ? defaultSupplier.get() : null)));
    }

    /**
     * Atomically computes a new component value given current state (or null).
     *
     * @param profile  The player profile to compute upon.
     * @param remapper Function taking existing component (or null) and returning new non-null component.
     * @return The newly stored component instance.
     */
    public @NotNull T compute(@NotNull final TachyonProfile profile, @NotNull final Function<@Nullable T, @NotNull T> remapper) {
        Objects.requireNonNull(profile, "profile cannot be null");
        return profile.computeComponent(this, remapper);
    }

    /**
     * Removes the component from the given profile (marks tombstone).
     *
     * @param profile The player profile to remove from.
     */
    public void remove(@NotNull final TachyonProfile profile) {
        Objects.requireNonNull(profile, "profile cannot be null");
        profile.removeComponent(this);
    }

    /**
     * Safely casts an arbitrary record instance to this key's type.
     *
     * @param instance The object to cast.
     * @return The typed component instance.
     */
    public @NotNull T cast(@NotNull final Object instance) {
        return type.cast(instance);
    }
}
