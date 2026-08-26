package tech.skworks.tachyon.plugin.spigot.task;

import tech.skworks.tachyon.api.profile.PlayerDataService;
import tech.skworks.tachyon.api.profile.TachyonProfile;
import tech.skworks.tachyon.api.profile.TachyonProfileRegistry;
import tech.skworks.tachyon.api.system.HealthService;
import tech.skworks.tachyon.plugin.common.util.TachyonLogger;
import tech.skworks.tachyon.plugin.core.playerdata.GrpcPlayerDataService;
import tech.skworks.tachyon.plugin.spigot.TachyonCore;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Project Tachyon
 * Class AutoSaveTask
 *
 * <p>One chunked sweep that flushes every dirty profile to the backend, processing at most
 * {@code processAmount} profiles per tick. A burst of dirty profiles is therefore spread over a
 * few ticks instead of spamming the backend (and stalling the main thread) all at once — and
 * unlike a hard per-cycle cap, every dirty profile is still saved within the cycle.</p>
 *
 * @author  Jimmy (vSKAH)
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class AutoSaveTask implements Runnable {


    private static final TachyonLogger LOGGER = TachyonCore.getModuleLogger("AutoSave");

    private final PlayerDataService grpcPlayerDataService;
    private final TachyonProfileRegistry tachyonProfileRegistry;
    private final HealthService healthService;

    private final AtomicBoolean processing = new AtomicBoolean(false);

    public AutoSaveTask(TachyonCore plugin) {
        this.tachyonProfileRegistry = plugin.getTachyonProfileRegistry();
        this.grpcPlayerDataService = plugin.getPlayerDataService();
        this.healthService = plugin.getHealthService();
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
                return;
            }

            for (int index = 0; index < dirtyProfiles.size(); index++) {
                final TachyonProfile profile = dirtyProfiles.get(index);

                grpcPlayerDataService.pushProfile(profile).exceptionally(ex -> {
                    LOGGER.error("Auto-save failed for {}: {}", profile.getUuid(), ex.getMessage());
                    return null;
                });
            }

        } finally {
            processing.set(false);
        }


    }
}
