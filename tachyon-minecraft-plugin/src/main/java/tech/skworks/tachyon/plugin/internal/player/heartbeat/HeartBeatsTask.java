package tech.skworks.tachyon.plugin.internal.player.heartbeat;

import tech.skworks.tachyon.plugin.plugin.config.TachyonConfig;

/**
 * Project Tachyon
 * Class HeartBeatsTask
 *
 * @author  Jimmy (vSKAH) - 13/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class HeartBeatsTask implements Runnable{

    private final HeartBeatService heartBeatService;
    private final boolean logBeats;

    public HeartBeatsTask(HeartBeatService heartBeatService, TachyonConfig tachyonConfig) {
        this.heartBeatService = heartBeatService;
        this.logBeats = tachyonConfig.logHeartBeats();
    }

    @Override
    public void run() {
        heartBeatService.sendHeartBeats(logBeats);
    }
}
