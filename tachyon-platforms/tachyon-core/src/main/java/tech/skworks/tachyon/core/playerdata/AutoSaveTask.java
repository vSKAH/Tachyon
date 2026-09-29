package tech.skworks.tachyon.core.playerdata;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.skworks.tachyon.api.profile.PlayerDataService;
import tech.skworks.tachyon.api.profile.TachyonProfile;
import tech.skworks.tachyon.api.profile.TachyonProfileRegistry;
import tech.skworks.tachyon.api.system.HealthService;

import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Project Tachyon
 * Class AutoSaveTask
 *
 * @author  Jimmy (vSKAH)
 * @version 2.0
 * @since 1.0.0-SNAPSHOT
 */
public class AutoSaveTask implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tachyon/AutoSaveTask");

    private final TachyonProfileRegistry tachyonProfileRegistry;
    private final PlayerDataService playerDataService;
    private final PlayerDataDlqService playerDataDlqService;
    private final HealthService healthService;

    private final AtomicBoolean processing;

    public AutoSaveTask(@NotNull final TachyonProfileRegistry tachyonProfileRegistry,
                        @NotNull final PlayerDataService playerDataService,
                        @NotNull final PlayerDataDlqService playerDataDlqService,
                        @NotNull final HealthService healthService) {

        this.tachyonProfileRegistry = Objects.requireNonNull(tachyonProfileRegistry, "tachyonProfileRegistry can't be null");
        this.playerDataService = Objects.requireNonNull(playerDataService, "playerDataService can't be null");
        this.healthService = Objects.requireNonNull(healthService, "healthService can't be null");
        this.playerDataDlqService = Objects.requireNonNull(playerDataDlqService, "playerDataDlqService can't be null");
        this.processing = new AtomicBoolean(false);
    }


    @Override
    public void run() {

        if (!processing.compareAndSet(false, true)) {
            return;
        }

        try {

            final var dirtyProfiles = tachyonProfileRegistry.getProfiles().stream().filter(TachyonProfile::hasPendingChanges).toList();

            if (dirtyProfiles.isEmpty()) {
                processing.set(false);
                return;
            }

            if (!healthService.isHealthy()) {
                for (final var profile : dirtyProfiles) {
                    playerDataDlqService.enqueue(PlayerDeltaAdapter.fromProfile(profile));
                }
                processing.set(false);
                return;
            }

            final var futures = new ArrayList<CompletableFuture<Void>>(dirtyProfiles.size());
            for (final var profile : dirtyProfiles) {
                futures.add(playerDataService.pushProfile(profile).exceptionally(ex -> {
                    LOGGER.error("Auto-save failed for {}: {}", profile.getUuid(), ex.getMessage());
                    return null;
                }));
            }

            CompletableFuture.allOf(futures.toArray(CompletableFuture<?>[]::new)).whenComplete((_, _) -> processing.set(false));
        } catch (Exception e) {
            LOGGER.error("Unexpected exception during auto-save execution", e);
            processing.set(false);
        }
    }
}
