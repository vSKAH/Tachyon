package tech.skworks.tachyon.core.playerdata;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tech.skworks.tachyon.api.component.ComponentKey;
import tech.skworks.tachyon.api.component.ComponentNamespace;

import java.util.Objects;

/**
 * Immutable slot representation capturing component state, key token, monotonic version, and tombstone status.
 * <p>
 * Fully aligned across Folia multithreading, zero-allocation JIT execution, and modern Java 25.
 * </p>
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @param key       The immutable type-safe component key token.
 * @param data      The immutable component record (null if tombstoned).
 * @param version   Monotonically increasing 64-bit revision number.
 * @param isDeleted Tombstone flag indicating component removal.
 *
 * @author Jimmy (vSKAH) - 11/11/2026
 * @version 2.0
 * @since 2.0.0-SNAPSHOT
 */
public record ComponentSlot(
        @NotNull ComponentKey<?> key,
        @Nullable Record data,
        long version,
        boolean isDeleted
) {
    public ComponentSlot {
        Objects.requireNonNull(key, "key cannot be null");
        if (!isDeleted && data == null) {
            throw new IllegalArgumentException("data cannot be null when isDeleted is false");
        }
    }

    public static ComponentSlot initial(@NotNull final ComponentKey<?> key, @NotNull final Record data, final long version) {
        return new ComponentSlot(key, data, version, false);
    }

    public static ComponentSlot tombstone(@NotNull final ComponentKey<?> key, final long version) {
        return new ComponentSlot(key, null, version, true);
    }

    public ComponentSlot withData(@NotNull final Record newData) {
        return new ComponentSlot(this.key, newData, this.version + 1L, false);
    }

    public ComponentSlot asDeleted() {
        return new ComponentSlot(this.key, null, this.version + 1L, true);
    }

    public @NotNull ComponentNamespace namespace() {
        return key.namespace();
    }
}
