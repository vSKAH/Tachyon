package tech.skworks.tachyon.core.playersession;

/**
 * Project Tachyon
 * Class PlayerSessionConfig
 *
 * @author  Jimmy (vSKAH) - 24/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public record PlayerSessionConfig(int heartbeatSendDelay, boolean logHeartbeatSend) {
}