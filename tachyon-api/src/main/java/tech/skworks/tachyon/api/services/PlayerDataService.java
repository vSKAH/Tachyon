package tech.skworks.tachyon.api.services;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tech.skworks.tachyon.api.profile.TachyonProfile;
import tech.skworks.tachyon.service.contracts.player.data.PullProfileResponse;

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
    @Nullable PullProfileResponse tryPullProfile(@NotNull final UUID uuid);

    @NotNull CompletableFuture<Void> pushProfile(@NotNull final TachyonProfile tachyonProfile);

    void flushQueueForPlayer(@NotNull final UUID uuid);
}
