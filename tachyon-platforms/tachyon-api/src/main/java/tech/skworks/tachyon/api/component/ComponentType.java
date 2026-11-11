package tech.skworks.tachyon.api.component;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Strongly-typed, immutable token representing a persistent player data component type.
 * <p>
 * Combines zero-indirection integer slot indexing in RAM with generic compile-time safety.
 * Follows the Single Responsibility Principle (SRP) as a pure data descriptor.
 * </p>
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @param slotIndex       The dense integer slot index (0 <= slotIndex < 128) allocated by ComponentRegistry.
 * @param namespace       The canonical persistent storage namespace of this component.
 * @param type            The Java 25 Record class representing this component state.
 * @param defaultSupplier Optional supplier evaluated lazily when the component is absent.
 * @param <T>             The component record type.
 *
 * @author Jimmy (vSKAH) - 11/11/2026
 * @version 2.0
 * @since 2.0.0-SNAPSHOT
 */
public record ComponentType<T extends Record>(
        int slotIndex,
        @NotNull ComponentNamespace namespace,
        @NotNull Class<T> type,
        @Nullable Supplier<T> defaultSupplier
) {
    public ComponentType {
        Objects.requireNonNull(namespace, "namespace cannot be null");
        Objects.requireNonNull(type, "type cannot be null");
        if (slotIndex < 0 || slotIndex >= 128) {
            throw new IllegalArgumentException("slotIndex must be between 0 and 127. Got: " + slotIndex);
        }
    }

    /**
     * Safely casts an arbitrary record instance to this component type.
     *
     * @param instance The object to cast.
     * @return The typed component instance.
     */
    public @NotNull T cast(@NotNull final Object instance) {
        return type.cast(instance);
    }
}
