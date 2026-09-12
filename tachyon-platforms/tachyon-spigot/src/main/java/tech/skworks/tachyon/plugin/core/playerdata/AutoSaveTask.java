package tech.skworks.tachyon.plugin.core.playerdata;

import org.jetbrains.annotations.NotNull;
import tech.skworks.tachyon.api.profile.PlayerDataService;
import tech.skworks.tachyon.api.profile.TachyonProfile;
import tech.skworks.tachyon.api.profile.TachyonProfileRegistry;
import tech.skworks.tachyon.api.system.HealthService;
import tech.skworks.tachyon.plugin.core.util.TachyonLogger;
import tech.skworks.tachyon.plugin.spigot.TachyonCore;

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


    private static final TachyonLogger LOGGER = TachyonCore.getModuleLogger("AutoSave");

    private final TachyonProfileRegistry tachyonProfileRegistry;
    private final PlayerDataService playerDataService;
    private final HealthService healthService;

    private final AtomicBoolean processing = new AtomicBoolean(false);

    public AutoSaveTask(@NotNull final TachyonProfileRegistry tachyonProfileRegistry,
                        @NotNull final PlayerDataService playerDataService,
                        @NotNull final HealthService healthService) {

        this.tachyonProfileRegistry = Objects.requireNonNull(tachyonProfileRegistry, "tachyonProfileRegistry can't be null");
        this.playerDataService = Objects.requireNonNull(playerDataService, "playerDataService can't be null");
        this.healthService = Objects.requireNonNull(healthService, "healthService can't be null");

    }


    @Override
    public void run() {

        if (!healthService.isHealthy()) {
            LOGGER.warn("Auto-Save Task is discontinued due to back-end health status.");
            return;
        }

        if (!processing.compareAndSet(false, true)) {
            return;
        }

        try {

            final var dirtyProfiles = tachyonProfileRegistry.getProfiles().stream().filter(TachyonProfile::hasPendingChanges).toList();

            if (dirtyProfiles.isEmpty()) {
                processing.set(false);
                return;
            }

            final var futures = new ArrayList<CompletableFuture<Void>>();

            for (final TachyonProfile profile : dirtyProfiles) {
                futures.add(playerDataService.pushProfile(profile).exceptionally(ex -> {
                    LOGGER.error("Auto-save failed for {}: {}", profile.getUuid(), ex.getMessage());
                    return null;
                }));
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).whenComplete((_, _) -> processing.set(false));
        }
        catch (Exception e) {
            LOGGER.error(e, "Unexpected exception during auto-save execution");
            processing.set(false);
        }
    }
}
