package tech.skworks.tachyon.core.profile.persistence;

import org.jetbrains.annotations.NotNull;
import tech.skworks.tachyon.core.profile.ComponentSlot;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable point-in-time snapshot of modified and deleted slots captured lock-free on-tick.
 * <p>
 * Transferred to Java 25 Virtual Threads for asynchronous off-tick BSON encoding and gRPC wire push.
 * </p>
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @param uuid  The player's unique identifier.
 * @param slots The immutable list of dirty/tombstoned component slots captured.
 *
 * @author Jimmy (vSKAH) - 11/11/2026
 * @version 2.0
 * @since 2.0.0-SNAPSHOT
 */
public record ProfileFlushBatch(
        @NotNull UUID uuid,
        @NotNull List<ComponentSlot> slots
) {
    public static final ProfileFlushBatch EMPTY = new ProfileFlushBatch(new UUID(0L, 0L), List.of());

    public ProfileFlushBatch {
        Objects.requireNonNull(uuid, "uuid cannot be null");
        Objects.requireNonNull(slots, "slots cannot be null");
    }

    public boolean isEmpty() {
        return slots.isEmpty();
    }
}
