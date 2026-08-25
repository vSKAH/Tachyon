package tech.skworks.tachyon.api.profile;

import org.bson.BsonDocument;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.UUID;

/**
 * Project Tachyon
 * Class TachyonProfileRegistry
 *
 * @author  Jimmy (vSKAH) - 21/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public interface TachyonProfileRegistry {

    void buildProfile(@NotNull final BsonDocument profileResponse, @NotNull final UUID uuid);

    boolean profileIsLoaded(@NotNull final UUID uuid);
    void unloadProfile(@NotNull final UUID uuid, @NotNull final String reason);
    void unloadProfile(@NotNull final UUID uuid);


    @Nullable TachyonProfile getProfile(@NotNull final UUID uuid);
    @NotNull Collection<TachyonProfile> getProfiles();

}
