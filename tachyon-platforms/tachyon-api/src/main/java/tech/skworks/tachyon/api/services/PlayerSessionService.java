package tech.skworks.tachyon.api.services;

import org.jetbrains.annotations.NotNull;
import tech.skworks.tachyon.api.profile.TachyonProfile;

import java.util.Collection;
import java.util.UUID;

/**
 * Project Tachyon
 * Class PlayerSessionService
 *
 * @author  Jimmy (vSKAH) - 22/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public interface PlayerSessionService {

    void sendHeartBeats(@NotNull final Collection<TachyonProfile> profiles, boolean log);
    void unlockPlayerProfile(@NotNull final UUID uuid, @NotNull final String playerName);

}
