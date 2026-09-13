package tech.skworks.tachyon.core.audit;


/**
 * Project Tachyon
 * Class AuditConfig
 *
 * @author  Jimmy (vSKAH) - 24/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public record AuditConfig(int bufferSize, int bufferFlushDelay, int bufferDrainPerCycles,
                          boolean enableLoginLogs, boolean enableLogoutLogs) {
}
