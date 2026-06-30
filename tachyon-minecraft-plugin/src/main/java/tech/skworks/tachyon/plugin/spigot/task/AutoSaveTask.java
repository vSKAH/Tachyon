package tech.skworks.tachyon.plugin.spigot.task;

import org.bukkit.plugin.java.JavaPlugin;
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
public class AutoSaveTask extends ChunkedTask {

    private final TachyonLogger logger;
    private final GrpcPlayerDataService grpcPlayerDataService;
    private final List<TachyonProfile> dirtyProfiles;

    public AutoSaveTask(JavaPlugin plugin, int processAmount, int waitPeriodTicks,
                        TachyonLogger logger, TachyonProfileRegistry tachyonProfileRegistry,
                        GrpcPlayerDataService grpcPlayerDataService) {
        super(processAmount, waitPeriodTicks, plugin);
        this.logger = logger;
        this.grpcPlayerDataService = grpcPlayerDataService;

        this.dirtyProfiles = tachyonProfileRegistry.getProfiles().stream()
                .filter(TachyonProfile::hasPendingChanges)
                .toList();

        setLogActions(false);
    }

    public boolean hasWork() {
        return !dirtyProfiles.isEmpty();
    }

    @Override
    protected boolean canContinue(int index) {
        return index < dirtyProfiles.size();
    }

    @Override
    protected void onProcess(int index) {
        final TachyonProfile profile = dirtyProfiles.get(index);

        if (!profile.hasPendingChanges()) return;

        grpcPlayerDataService.pushProfile(profile)
                .exceptionally(ex -> {
                    logger.error("Auto-save failed for {}: {}", profile.getUuid(), ex.getMessage());
                    return null;
                });
    }

    @Override
    protected String getLabel() {
        return "profile(s)";
    }
}
