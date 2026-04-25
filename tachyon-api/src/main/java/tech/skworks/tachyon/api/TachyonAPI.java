package tech.skworks.tachyon.api;

import tech.skworks.tachyon.api.component.ComponentRegistry;
import tech.skworks.tachyon.api.profile.TachyonProfileRegistry;
import tech.skworks.tachyon.api.services.*;

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
 * @param <V> The visual object type used by the {@link ComponentRegistry} and UI systems
 * (e.g., {@code org.bukkit.inventory.ItemStack} for a Spigot implementation).
 *
 * @author  Jimmy (vSKAH) - 13/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public interface TachyonAPI<V> {

    //TODO: add doc
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
    ComponentRegistry<V> getComponentRegistry();

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

    PlayerSessionService getPlayerSessionService();

    PlayerDataService getPlayerDataService();

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
