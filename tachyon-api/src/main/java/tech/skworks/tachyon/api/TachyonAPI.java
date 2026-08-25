package tech.skworks.tachyon.api;

import tech.skworks.tachyon.api.audit.AuditService;
import tech.skworks.tachyon.api.component.ComponentRegistry;
import tech.skworks.tachyon.api.event.EventBus;
import tech.skworks.tachyon.api.profile.PlayerDataService;
import tech.skworks.tachyon.api.profile.TachyonProfileRegistry;
import tech.skworks.tachyon.api.services.*;
import tech.skworks.tachyon.api.snapshot.SnapshotService;

/**
 * Project Tachyon
 * Interface TachyonAPI
 *
 * <p> The primary gateway and central API for the Tachyon ecosystem. </p>
 * <p>
 * This interface provides external plugins and modules with safe access to Tachyon's
 * core services, including live player profile management, component registration,
 * telemetry auditing, and the snapshot backup system.
 * </p>
 *
 *
 * @author  Jimmy (vSKAH) - 13/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public interface TachyonAPI {

    /**
     * Retrieves the registry responsible for managing live player profiles.
     * <p>
     * Used to access, load, or manipulate active {@link tech.skworks.tachyon.api.profile.TachyonProfile}
     * instances that are currently cached in the server's memory.
     * </p>
     *
     * @return The active {@link TachyonProfileRegistry} instance.
     */
    TachyonProfileRegistry getTachyonProfileRegistry();

    /**
     * Retrieves the central component registry used to manage Protobuf descriptors
     * and UI preview handlers.
     * <p>
     * This registry is essential for registering new custom data components when your
     * specific plugin or module initializes during server startup.
     *
     * @return The active {@link ComponentRegistry} instance.
     */
    ComponentRegistry getComponentRegistry();

    /**
     * Retrieves the system service handling core backend connectivity and global states.
     * <p>
     * This service manages low-level operations such as back-end ping.
     * </p>
     *
     * @return The active {@link SystemService} instance.
     */
    SystemService getSystemService();

    /**
     * Retrieves the auditing service used to dispatch player actions and security events
     * to the backend.
     *
     * @return The active {@link AuditService} instance.
     */
    AuditService getAuditService();

    /**
     * Retrieves the snapshot service used to trigger database backups,
     * point-in-time recoveries, and to query snapshot histories.
     *
     * @return The active {@link SnapshotService} instance.
     */
    SnapshotService getSnapshotService();

    /**
     * Retrieves the service responsible for managing active player sessions across the network.
     * <p>
     * This service handles keeping sessions alive by sending periodic heartbeats for loaded profiles,
     * and provides mechanisms to unlock player profiles to prevent stuck sessions or cross-server locks.
     * </p>
     *
     * @return The active {@link PlayerSessionService} instance.
     */
    PlayerSessionService getPlayerSessionService();

    /**
     * Retrieves the service dedicated to raw player data manipulation and synchronization.
     * <p>
     * This service is utilized to pull profile data from the backend, asynchronously push
     * profile updates (saves), and aggressively flush pending data queues for specific players.
     * </p>
     *
     * @return The active {@link PlayerDataService} instance.
     */
    PlayerDataService getPlayerDataService();

    /**
     * Retrieves the central asynchronous event bus used to dispatch and handle Tachyon events.
     * <p>
     * This event bus handles safe listener registration and high-performance, lock-free
     * execution offloaded to a dedicated asynchronous thread pool.
     * </p>
     *
     * @return The active {@link EventBus} instance.
     */
    EventBus getEventBus();

    /**
     * Checks if the Tachyon core system is currently in its shutdown phase.
     * <p>
     * <b>Best Practice:</b> External plugins should check this boolean before initiating
     * any new asynchronous gRPC calls, component saves, or heavy operations. If this returns
     * {@code true}, it means the server is stopping and new backend requests should be aborted
     * to prevent hanging threads or data corruption.
     *
     * @return {@code true} if the Tachyon plugin is actively disabling, {@code false} otherwise.
     */
    boolean tachyonCoreDisabling();
}
