package tech.skworks.tachyon.api.snapshot;

import org.jetbrains.annotations.NotNull;
import tech.skworks.tachyon.api.component.ComponentNamespace;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Project Tachyon
 * Interface SnapshotService
 *
 * <p> The core service responsible for managing player data backups (snapshots) and restorations. </p>
 * <p>
 * This service acts as the bridge between the Spigot server and the Quarkus backend via gRPC.
 * It handles the creation of point-in-time backups (both full profiles and specific components)
 * and provides the necessary methods to fetch and decode snapshot histories for administrative reviews.
 * </p>
 *
 * @author  Jimmy (vSKAH) - 17/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public interface SnapshotService {

    /**
     * Triggers an asynchronous backup of a player's entire profile based on the current database state.
     * <p>
     * <b>Important Warning:</b> This method snapshots the data exactly as it currently exists
     * in the backend database, <b>not</b> the live data present in the Spigot server's memory.
     * If the player has "dirty" components that have not yet been flushed to the backend
     * (via {@code saveProfile()} or {@code saveComponent()}), those unsaved changes will
     * <b>not</b> be included in this snapshot.
     * <p>
     * This creates a snapshot with {@code FULL} granularity. Because it serializes
     * every piece of data currently attached to the player's database document, it should be
     * used for major events (e.g., player bans, manual admin backups, or major scheduled saves).
     *
     * @param playerUniqueId The UUID of the player whose profile is being snapshotted.
     * @param reason         The human-readable reason for this snapshot (e.g., "Suspected duplication").
     * @param triggerType    The system or user action that triggered this backup (e.g., MANUAL, SPIGOT_TASK).
     * @return A CompletableFuture that completes when the backend has successfully saved the full snapshot.
     */
    CompletableFuture<Void> takeDatabaseSnapshot(@NotNull final String playerUniqueId, @NotNull final String reason, @NotNull final SnapshotTriggerType triggerType);

    /**
     * Triggers an asynchronous backup of a single, specific component for a player.
     * <p>
     * This creates a snapshot with {@code SPECIFIC_COMPONENT} granularity in the database.
     * The component record is encoded into pure BSON via its registered {@link tech.skworks.tachyon.api.component.ComponentCodec}
     * and sent to the backend stream worker.
     * </p>
     *
     * @param playerUniqueId The UUID of the player associated with the data. Must not be null.
     * @param reason         The human-readable reason for this specific snapshot. Must not be null.
     * @param triggerType    The system or user action that triggered this backup. Must not be null.
     * @param component      The specific component record instance containing the data to backup. Must not be null.
     * @param <T>            The specific type of the component record.
     * @return A CompletableFuture that completes when the backend has successfully saved the specific snapshot.
     */
    <T extends Record> CompletableFuture<Void> takeComponentSnapshot(@NotNull final String playerUniqueId, @NotNull final String reason, @NotNull final SnapshotTriggerType triggerType, @NotNull final T component);


    /**
     * Asynchronously toggles the lock status of a specific snapshot.
     * <p>
     * A locked snapshot is protected from being automatically purged or archived by
     * scheduled maintenance tasks (such as the S3 Archiver job). This method
     * inverses the current lock state (locks an unlocked snapshot, or unlocks a locked one).
     * </p>
     *
     * @param snapshotId       The unique identifier (ObjectId hex string) of the target snapshot. Must not be null.
     * @param executorUniqueId The unique identifier (e.g., UUID) of the administrator or system executing this action. Must not be null.
     * @return A {@link CompletableFuture} that, when successfully completed, yields a boolean
     * containing the newly applied lock status.
     */
    CompletableFuture<Boolean> toggleSnapshotLocking(@NotNull final String snapshotId, @NotNull final String executorUniqueId);

    /**
     * Retrieves the list of days for which snapshots exist for a specific player, along with the count of saves per day.
     *
     * @param playerUniqueId The UUID of the player whose snapshot days are being requested, not null.
     * @return A CompletableFuture containing the list of available snapshot days with their metadata.
     */
    CompletableFuture<List<SnapshotDayInfo>> getSnapshotDays(@NotNull final String playerUniqueId);

    /**
     * Retrieves the lightweight metadata for all snapshots recorded on a specific calendar day for a given player.
     *
     * @param playerUniqueId The UUID of the player whose snapshots are being requested, not null.
     * @param date           The target calendar date, not null.
     * @return A CompletableFuture containing the list of snapshot metadata for that day.
     */
    CompletableFuture<List<SnapshotInfo>> getSnapshotsForDay(@NotNull final String playerUniqueId, @NotNull final LocalDate date);

    /**
     * Fetches and decodes the actual data payload of a specific snapshot from the backend.
     * <p>
     * The backend retrieves the raw BSON document from MongoDB, extracts the component subdocuments,
     * and transfers them across the wire. Each component is subsequently deserialized into its
     * corresponding Java {@link Record} via the {@link tech.skworks.tachyon.api.component.ComponentRegistry}.
     * </p>
     *
     * @param snapshotId The unique MongoDB ObjectId string of the snapshot to retrieve. Must not be null.
     * @return A CompletableFuture containing an immutable map of decoded component records keyed by namespace.
     */
    CompletableFuture<Map<ComponentNamespace, Record>> decodeSnapshot(@NotNull final String snapshotId);

    /**
     * Fetches and decodes a specific component from a snapshot.
     *
     * @param snapshotId The unique MongoDB ObjectId string of the snapshot to retrieve. Must not be null.
     * @param namespace  The namespace of the component to retrieve. Must not be null.
     * @param <T>        The record type of the component.
     * @return A CompletableFuture containing the decoded component record, or null if not present in the snapshot.
     */
    <T extends Record> CompletableFuture<T> decodeSnapshotComponent(@NotNull final String snapshotId, @NotNull final ComponentNamespace namespace);

}
