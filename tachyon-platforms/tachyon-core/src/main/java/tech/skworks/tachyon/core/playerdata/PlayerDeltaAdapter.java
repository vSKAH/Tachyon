package tech.skworks.tachyon.core.playerdata;

import org.jetbrains.annotations.NotNull;
import tech.skworks.tachyon.api.profile.TachyonProfile;
import tech.skworks.tachyon.common.player.PlayerDelta;

import java.util.Objects;

/**
 * Short class explaination
 * <p>
 * Long class explaination with @link if needed
 * </p>
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @author Jimmy (vSKAH) - 21/09/2026
 * @version 1.0
 * @since 2.0.0-alpha.2
 */
public final class PlayerDeltaAdapter {

    private PlayerDeltaAdapter() {}

    public static @NotNull PlayerDelta fromProfile(@NotNull final TachyonProfile profile) throws NullPointerException{
        Objects.requireNonNull(profile, "profile cannot be null");
        final var delta = new PlayerDelta(profile.getUuid());

        profile.extractDirtyComponents().forEach((namespace, document) ->
                delta.addSave(namespace.fullNamespace(), document, profile.getVersion(namespace)));
        profile.extractDeletedComponents().forEach(namespace ->
                delta.addRemove(namespace.fullNamespace(), profile.getVersion(namespace)));

        return delta;
    }
}
