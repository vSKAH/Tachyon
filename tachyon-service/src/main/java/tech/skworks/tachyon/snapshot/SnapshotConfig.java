package tech.skworks.tachyon.snapshot;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Project Tachyon
 * Class SnapshotConfig
 *
 * @author  Jimmy (vSKAH) - 06/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
@ConfigMapping(prefix = "tachyon.snapshot")
public interface SnapshotConfig {
    String playersCollection();
    String snapshotsCollection();

    @WithDefault("tachyon")
    String s3Bucket();

    @WithDefault("7")
    int retentionDays();
    @WithDefault("10")
    int maxConcurrentUploads();
    @WithDefault("300")
    int throttleSeconds();
}
