package tech.skworks.tachyon.api.component;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Strongly-typed, immutable token representing a persistent player data component.
 * <p>
 * Acts purely as a descriptor token holding dense slot index, canonical namespace,
 * component Record class type, and an optional fallback supplier. Follows Single Responsibility Principle (SRP).
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
     * Safely casts an arbitrary record instance to this key's type.
     *
     * @param instance The object to cast.
     * @return The typed component instance.
     */
    public @NotNull T cast(@NotNull final Object instance) {
        return type.cast(instance);
    }
}
