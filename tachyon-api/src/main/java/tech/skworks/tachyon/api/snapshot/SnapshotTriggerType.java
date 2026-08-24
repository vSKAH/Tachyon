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
public enum SnapshotTriggerType {

    UNSPECIFIED,
    SPIGOT_TASK,
    MANUAL,
    OTHER,
    UNKNOWN;


    public static SnapshotTriggerType fromString(String raw) {
        if (raw == null) return UNKNOWN;
        try {
            return SnapshotTriggerType.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
