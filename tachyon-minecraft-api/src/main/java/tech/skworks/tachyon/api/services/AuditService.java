package tech.skworks.tachyon.api.services;

import org.jetbrains.annotations.NotNull;

/**
 * Project Tachyon
 * Interface AuditService
 *
 * <p> The core telemetry and auditing contract for the Tachyon ecosystem. </p>
 * <p>
 * This service is responsible for securely recording and dispatching player actions,
 * security events, and critical state changes. These logs are typically forwarded
 * to the backend (via gRPC) for moderation, analytics, or debugging purposes.
 * </p>
 *
 * @author  Jimmy (vSKAH) - 17/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public interface AuditService {

    /**
     * Records a specific action performed by or affecting a player, along with its context.
     * <p>
     * This method is designed to be fire-and-forget. The underlying implementation
     * handle the asynchronous dispatching of the log to prevent any blocking
     * on the main server thread.
     *
     * @param uuid    The unique identifier (UUID) of the player associated with this event.
     * @param action  A clear, categorizable identifier for the event
     * (e.g., {@code "TRADE_ACCEPT"}, {@code "ITEM_DROP"}, {@code "FLAG_TRIGGERED"}).
     * @param details Additional contextual information about the event.
     */
    void log(@NotNull final String uuid, @NotNull final String action, @NotNull final String details);

}
