package tech.skworks.tachyon.api.profile;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tech.skworks.tachyon.libs.org.bson.BsonDocument;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Project Tachyon
 * Class PlayerDataService
 *
 * @author  Jimmy (vSKAH) - 24/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public interface PlayerDataService {
    @Nullable BsonDocument tryPullProfile(@NotNull final UUID uuid);

    @NotNull CompletableFuture<Void> pushProfile(@NotNull final TachyonProfile tachyonProfile);

    void flushQueueForPlayer(@NotNull final UUID uuid);
}
