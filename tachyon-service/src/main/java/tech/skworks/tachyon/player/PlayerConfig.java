package tech.skworks.tachyon.player;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Project Tachyon
 * Class PlayerConfig
 *
 * @author  Jimmy (vSKAH) - 06/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
@ConfigMapping(prefix = "tachyon.player")
public interface PlayerConfig {

    @WithDefault("tachyon:player_stream") String streamKey();
    @WithDefault("tachyon:player_group") String streamGroupName();
    @WithDefault("tachyon:player_worker_1") String consumerId();

    @WithDefault("players") String collection();
}
