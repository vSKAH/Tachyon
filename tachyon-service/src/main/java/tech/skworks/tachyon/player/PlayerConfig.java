package tech.skworks.tachyon.player;

import io.smallrye.config.ConfigMapping;

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
    String streamKey();
    String collection();
    String snapshotsCollection();
}
