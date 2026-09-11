package tech.skworks.tachyon.api.event.profile;

import org.jetbrains.annotations.NotNull;
import tech.skworks.tachyon.api.event.TachyonEvent;
import tech.skworks.tachyon.api.profile.TachyonProfile;
import tech.skworks.tachyon.api.profile.TachyonProfileRegistry;

import java.util.UUID;

/**
 * Triggered when a player's profile is fully loaded from the backend.
 * <p>
 * This event is fired by the {@link tech.skworks.tachyon.api.profile.TachyonProfileRegistry}
 * once all the component data has been retrieved, unpacked, and safely cached in memory.
 * </p>
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @param playerUuid The unique identifier of the player whose profile was loaded.
 * @param profile    The active profile instance that is now cached in the registry.
 *
 * @author Jimmy (vSKAH) - 20/05/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public record ProfileLoadedEvent(@NotNull UUID playerUuid, @NotNull TachyonProfile profile) implements TachyonEvent {
}