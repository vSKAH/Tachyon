package tech.skworks.tachyon.api.services;

import org.jetbrains.annotations.NotNull;
import tech.skworks.tachyon.service.contracts.snapshot.DecodeSnapshotResponse;
import tech.skworks.tachyon.service.contracts.snapshot.ListSnapshotsResponse;
import tech.skworks.tachyon.service.contracts.snapshot.SnapshotTriggerType;
import tech.skworks.tachyon.libs.com.google.protobuf.Message;
import tech.skworks.tachyon.service.contracts.snapshot.ToggleLockSnapshotResponse;

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
     * Triggers an asynchronous backup of a single, specific Protobuf component for a player.
     * <p>
     * This creates a snapshot with {@code SPECIFIC_COMPONENT} granularity in the database.
     * It is highly optimized, sending only the compressed raw bytes (Zstd) of the targeted component
     * over the network. Ideal for frequent, highly targeted backups (e.g., saving an inventory right after a trade).
     *
     * @param playerUniqueId The UUID of the player associated with the data.
     * @param reason         The human-readable reason for this specific snapshot.
     * @param triggerType    The system or user action that triggered this backup.
     * @param component      The specific Protobuf message instance containing the data to backup.
     * @param <T>            The specific type of the Protobuf message.
     * @return A CompletableFuture that completes when the backend has successfully saved the specific snapshot.
     */
    <T extends Message> CompletableFuture<Void> takeComponentSnapshot(@NotNull final String playerUniqueId, @NotNull final String reason, @NotNull final SnapshotTriggerType triggerType, @NotNull final T component);


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
     * @return A {@link CompletableFuture} that, when successfully completed, yields a {@link ToggleLockSnapshotResponse}
     * containing the newly applied lock status.
     */
    CompletableFuture<ToggleLockSnapshotResponse> toggleSnapshotLocking(@NotNull final String snapshotId, @NotNull final String executorUniqueId);

    /**
     * Retrieves the metadata history of all snapshots associated with a specific player.
     * <p>
     * This method does <b>not</b> download the actual heavy data payloads. It only fetches
     * the lightweight metadata (IDs, timestamps, reasons, granularity) used to populate
     * the administrative Snapshot GUI lists.
     *
     * @param playerUniqueId The UUID of the player whose snapshot history is being requested.
     * @return A CompletableFuture containing the response with the list of snapshot metadata.
     */
    CompletableFuture<ListSnapshotsResponse> getSnapshots(@NotNull final String playerUniqueId);


    /**
     * Fetches and decodes the actual data payload of a specific snapshot from the backend.
     * <p>
     * Whether the snapshot is {@code FULL} (JSON parsed) or {@code SPECIFIC_COMPONENT} (Raw Binary),
     * the backend will process it and return a standardized response containing the decoded
     * Protobuf components wrapped in {@link com.google.protobuf.Any} objects, ready to be
     * processed by the {@link tech.skworks.tachyon.api.registries.ComponentRegistry}.
     *
     * @param snapshotId The unique MongoDB ObjectId string of the snapshot to retrieve.
     * @return A CompletableFuture containing the decoded snapshot data payload.
     */
    CompletableFuture<DecodeSnapshotResponse> decodeSnapshot(@NotNull final String snapshotId);

}
