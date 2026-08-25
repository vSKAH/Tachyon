package tech.skworks.tachyon.api.audit;

/**
 * Defines the criticality and delivery level of an {@link AuditEntry}.
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @author Jimmy (vSKAH) - 25/08/2026
 * @version 1.0
 * @since 2.0.0-SNAPSHOT
 */
public enum AuditLevel {
    /** High-frequency gameplay events (e.g. movement, minor clicks, inventory shuffling). */
    LOW,

    /** Standard informative events (e.g. login, logout, economy changes, routine commands). Default level. */
    NORMAL,

    /** High-priority or security-sensitive events (e.g. bans, real-money transactions, admin permission changes). */
    CRITICAL
}
