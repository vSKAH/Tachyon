package tech.skworks.tachyon.plugin.spigot.task;

import tech.skworks.tachyon.api.profile.TachyonProfile;
import tech.skworks.tachyon.api.profile.TachyonProfileRegistry;
import tech.skworks.tachyon.plugin.common.util.TachyonLogger;
import tech.skworks.tachyon.plugin.core.playerdata.GrpcPlayerDataService;

import java.util.List;

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

    private final TachyonLogger logger;
    private final GrpcPlayerDataService grpcPlayerDataService;
    private final TachyonProfileRegistry tachyonProfileRegistry;
    private boolean processing = false;

    public AutoSaveTask(TachyonLogger logger, TachyonProfileRegistry tachyonProfileRegistry,
                        GrpcPlayerDataService grpcPlayerDataService) {
        this.logger = logger;
        this.tachyonProfileRegistry = tachyonProfileRegistry;
        this.grpcPlayerDataService = grpcPlayerDataService;

    }


    @Override
    public void run() {


        if (processing) return;

        processing = true;
        List<TachyonProfile> dirtyProfiles = tachyonProfileRegistry.getProfiles().stream()
                .filter(TachyonProfile::hasPendingChanges)
                .toList();


        if (dirtyProfiles.isEmpty()) {
            processing = false;
            return;
        }


        for (int index = 0; index < dirtyProfiles.size(); index++) {
            final TachyonProfile profile = dirtyProfiles.get(index);

            if (!profile.hasPendingChanges()) continue;

            grpcPlayerDataService.pushProfile(profile)
                    .exceptionally(ex -> {
                        logger.error("Auto-save failed for {}: {}", profile.getUuid(), ex.getMessage());
                        return null;
                    });
        }

        processing = false;

    }
}
