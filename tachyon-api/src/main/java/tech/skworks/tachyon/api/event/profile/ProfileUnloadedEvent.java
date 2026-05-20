package tech.skworks.tachyon.api.event.profile;

import org.jetbrains.annotations.NotNull;
import tech.skworks.tachyon.api.event.TachyonEvent;
import tech.skworks.tachyon.api.profile.TachyonProfile;

import java.util.UUID;

/**
 * Triggered when a player's profile is removed from the active registry cache.
 * <p>
 * This event is fired by the {@link tech.skworks.tachyon.api.profile.TachyonProfileRegistry}
 * when a profile is explicitly unloaded (e.g., player disconnects, login denied, or manual reload).
 * </p>
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @param playerUuid The unique identifier of the unloaded player.
 * @param profile    The profile instance that was just removed from cache.
 * @param reason     The explicit reason for this unload operation (e.g., "DISCONNECT", "LOGIN_DENIED").
 *
 * @author Jimmy (vSKAH) - 20/05/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public record ProfileUnloadedEvent(@NotNull UUID playerUuid, @NotNull TachyonProfile profile,
                                   @NotNull String reason) implements TachyonEvent {
}