package tech.skworks.tachyon.core.playerdata;

import org.bson.BsonDocument;
import org.jetbrains.annotations.NotNull;
import tech.skworks.tachyon.api.component.ComponentCodec;
import tech.skworks.tachyon.api.component.ComponentRegistry;
import tech.skworks.tachyon.common.player.PlayerDelta;

import java.util.Objects;

/**
 * Adapter converting lock-free ProfileFlushBatch instances into PlayerDelta wire models.
 * <p>
 * Ensures BSON encoding is decoupled from the game loop and executed inside Virtual Threads.
 * </p>
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @author Jimmy (vSKAH) - 21/09/2026
 * @version 2.0
 * @since 2.0.0-alpha.2
 */
public final class PlayerDeltaAdapter {

    private PlayerDeltaAdapter() {}

    /**
     * Constructs a PlayerDelta from an immutable ProfileFlushBatch using registered codecs.
     * <p>
     * Executed strictly off-tick within Java 25 Virtual Threads.
     * </p>
     *
     * @param snapshot The immutable profile snapshot.
     * @param registry The component registry holding codecs.
     * @return The populated, ready-to-serialize PlayerDelta.
     */
    public static @NotNull PlayerDelta fromFlushBatch(@NotNull final ProfileFlushBatch snapshot,
                                                    @NotNull final ComponentRegistry registry) {
        Objects.requireNonNull(snapshot, "snapshot cannot be null");
        Objects.requireNonNull(registry, "registry cannot be null");

        final var delta = new PlayerDelta(snapshot.uuid());

        for (final ComponentSlot slot : snapshot.slots()) {
            final var namespace = slot.namespace();
            final long version = slot.version();

            if (slot.isDeleted() || slot.data() == null) {
                delta.addRemove(namespace.fullNamespace(), version);
            } else {
                final ComponentCodec<?> codec = registry.getCodec(namespace);
                if (codec != null) {
                    final BsonDocument doc = encodeUnchecked(codec, slot.data());
                    delta.addSave(namespace.fullNamespace(), doc, version);
                }
            }
        }

        return delta;
    }

    @SuppressWarnings("unchecked")
    private static <R extends Record> BsonDocument encodeUnchecked(final ComponentCodec<R> codec, final Record data) {
        return codec.encode((R) data);
    }
}
