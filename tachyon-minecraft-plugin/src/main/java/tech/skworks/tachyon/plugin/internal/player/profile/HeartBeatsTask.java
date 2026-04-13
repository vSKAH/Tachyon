package tech.skworks.tachyon.plugin.internal.player.profile;

import tech.skworks.tachyon.contracts.player.PlayerRequest;
import tech.skworks.tachyon.plugin.internal.config.TachyonConfig;

import java.util.List;

/**
 * Project Tachyon
 * Class HeartBeatsTask
 *
 * @author  Jimmy (vSKAH) - 13/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class HeartBeatsTask implements Runnable{

    private final PlayerProfileService playerProfileService;
    private final boolean logBeats;

    public HeartBeatsTask(PlayerProfileService playerProfileService, TachyonConfig tachyonConfig) {
        this.playerProfileService = playerProfileService;
        this.logBeats = tachyonConfig.logHeartBeats();
    }

    @Override
    public void run() {
        List<PlayerRequest> beats = playerProfileService.buildHeartBeats();
        if (beats.isEmpty()) return;
        playerProfileService.sendHeartBeats(beats, logBeats);
        beats.clear();
    }
}
