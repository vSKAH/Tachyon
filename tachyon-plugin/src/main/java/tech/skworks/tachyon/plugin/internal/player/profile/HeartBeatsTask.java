package tech.skworks.tachyon.plugin.internal.player.profile;

import tech.skworks.tachyon.contracts.player.PlayerRequest;

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

    public HeartBeatsTask(PlayerProfileService playerProfileService) {
        this.playerProfileService = playerProfileService;
    }

    @Override
    public void run() {
        List<PlayerRequest> beats = playerProfileService.buildHeartBeats();
        if (beats.isEmpty()) return;
        playerProfileService.sendHeartBeats(beats);
        beats.clear();
    }
}
