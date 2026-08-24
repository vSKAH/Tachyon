package tech.skworks.tachyon.api.snapshot;

/**
 * Short class explaination
 * <p>
 * Long class explaination with @link if needed
 * </p>
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @author Jimmy (vSKAH) - 24/08/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public record SnapshotInfo(
        String snapshotId,
        long timestamp,
        String reason,
        String source,
        SnapshotTriggerType triggerType,
        String granularity,
        boolean locked
) {}
