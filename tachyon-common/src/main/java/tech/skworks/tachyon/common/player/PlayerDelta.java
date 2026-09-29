package tech.skworks.tachyon.common.player;

import org.bson.*;
import tech.skworks.tachyon.common.BsonUtil;
import tech.skworks.tachyon.common.contract.PlayerDataContract;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe carrier representing an isolated batch of mutations applied to a player profile
 * <p>
 * Instead of transmitting and rewriting an entire player profile across the network,
 * {@code PlayerDelta} captures only the granular modifications (the "deltas") that actually took place :
 * </p>
 * <ul>
 *   <li><b>Saves:</b> Components that were created or updated (wallet coins incremented, inventory slot changed, ...)</li>
 *   <li><b>Removes:</b> Components that were deleted</li>
 *   <li><b>Versions:</b> Increasing version numbers used by {@link PlayerProfileMerger} to prevent stale overwrites</li>
 * </ul>
 * <p>
 * This class is thread-safe non-blocking, and handles serialization to and from raw BSON documents
 * for gRPC transport and others
 * </p>
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @author Jimmy (vSKAH) - 21/09/2026
 * @version 1.0
 * @since 2.0.0-alpha.2
 */
public final class PlayerDelta {

    private final UUID playerUuid;
    private final Map<String, BsonDocument> saves;
    private final Set<String> removes;
    private final Map<String, Long> versions;
    private volatile long lastModifiedTimestamp;

    /**
     * Creates a new, empty change tracker for the specified player
     *
     * @param playerUuid The unique identifier of the player whose changes are tracked
     * @throws NullPointerException If {@code playerUuid} is null
     */
    public PlayerDelta(final UUID playerUuid) throws NullPointerException {
        this.playerUuid = Objects.requireNonNull(playerUuid, "playerUuid cannot be null");
        this.saves = new ConcurrentHashMap<>();
        this.removes = ConcurrentHashMap.newKeySet();
        this.versions = new ConcurrentHashMap<>();
        this.lastModifiedTimestamp = System.currentTimeMillis();
    }

    /**
     * Reconstructs {@link PlayerDelta} from a raw incoming BSON document
     * <p>
     * Expects a document containing the standard {@code uuid}, {@code save}, {@code remove},
     * and {@code versions} sections defined by {@link PlayerDataContract}
     * </p>
     *
     * @param document The BSON document containing the serialized delta payload
     * @return A newly initialized {@link PlayerDelta} populated with the document's contents
     * @throws NullPointerException     If {@code document} is null
     * @throws IllegalArgumentException If the document lacks a valid {@code uuid} field
     */
    public static PlayerDelta fromBson(final BsonDocument document) throws NullPointerException, IllegalArgumentException {
        Objects.requireNonNull(document, "document cannot be null");

        final var uuidField = PlayerDataContract.FIELD_UUID;
        if (!document.containsKey(uuidField) || !document.isString(uuidField)) {
            throw new IllegalArgumentException("Document does not contain a valid 'uuid' string field");
        }

        final var uuidString = document.getString(uuidField).getValue();
        final var playerUuid = UUID.fromString(uuidString);
        final var delta = new PlayerDelta(playerUuid);
        delta.applyDeltaDocument(document);
        return delta;
    }

    /**
     * Records a modified or added component within this delta
     * <p>
     * If this component was previously marked for deletion in this delta, the deletion flag is cleared
     * If an incoming update has a version number that is older than or equal to what is already stored,
     * it will be safely ignored to prevent version regressions
     * </p>
     *
     * @param componentName     The namespace of the component (e.g., {@code "myplugin:inventory"})
     * @param componentDocument The BSON payload representing the component updated state
     * @param componentVersion  The incremental version number associated with this specific modification
     * @throws NullPointerException If {@code componentName} or {@code componentDocument} is null
     */
    public void addSave(final String componentName, final BsonDocument componentDocument, final long componentVersion) throws NullPointerException {
        Objects.requireNonNull(componentName, "componentName cannot be null");
        Objects.requireNonNull(componentDocument, "componentDocument cannot be null");

        final Long currentVersion = versions.get(componentName);
        if (PlayerProfileMerger.isStale(componentVersion, currentVersion)) {
            return;
        }

        saves.put(componentName, componentDocument);
        removes.remove(componentName);
        versions.put(componentName, componentVersion);
        this.lastModifiedTimestamp = System.currentTimeMillis();
    }

    /**
     * Marks a component for deletion within this delta
     * <p>
     * If this component had pending save data in this delta, the save data is ignored
     * Oldest deletion requests with older versions are automatically rejected
     * </p>
     *
     * @param componentName    The canonical namespace of the component to remove (e.g., {@code "id:temporary_buff"})
     * @param componentVersion The version number associated with this deletion event
     * @throws NullPointerException If {@code componentName} is null
     */
    public void addRemove(final String componentName, final long componentVersion) throws NullPointerException {
        Objects.requireNonNull(componentName, "componentName cannot be null");

        final Long currentVersion = versions.get(componentName);
        if (PlayerProfileMerger.isStale(componentVersion, currentVersion)) {
            return;
        }

        removes.add(componentName);
        saves.remove(componentName);
        versions.put(componentName, componentVersion);
        this.lastModifiedTimestamp = System.currentTimeMillis();
    }

    /**
     * Merges a raw BSON delta document into this delta instance
     * <p>
     * Inspects the document for {@code save}, {@code remove}, and {@code versions} blocks,
     * applying each mutation while honoring monotonic version ordering
     * </p>
     *
     * @param incomingDeltaDocument The BSON document to incorporate
     * @throws NullPointerException If {@code incomingDeltaDocument} is null
     */
    public void applyDeltaDocument(final BsonDocument incomingDeltaDocument) throws NullPointerException {
        Objects.requireNonNull(incomingDeltaDocument, "incomingDeltaDocument cannot be null");

        final var incomingVersions = PlayerProfileMerger.extractVersions(incomingDeltaDocument);
        final var saveField = PlayerDataContract.FIELD_SAVE;
        final var removeField = PlayerDataContract.FIELD_REMOVE;

        if (incomingDeltaDocument.containsKey(saveField) && incomingDeltaDocument.isDocument(saveField)) {
            final var incomingSaves = incomingDeltaDocument.getDocument(saveField);
            for (final var componentName : incomingSaves.keySet()) {
                final Long incomingVersion = incomingVersions.get(componentName);
                final long versionToApply = incomingVersion != null ? incomingVersion : 1L;
                addSave(componentName, incomingSaves.getDocument(componentName), versionToApply);
            }
        }

        if (incomingDeltaDocument.containsKey(removeField) && incomingDeltaDocument.isArray(removeField)) {
            final var incomingRemoves = incomingDeltaDocument.getArray(removeField);
            for (final var arrayElement : incomingRemoves) {
                if (!arrayElement.isString()) {
                    continue;
                }
                final var componentName = arrayElement.asString().getValue();
                final Long incomingVersion = incomingVersions.get(componentName);
                final long versionToApply = incomingVersion != null ? incomingVersion : 1L;
                addRemove(componentName, versionToApply);
            }
        }
    }

    /**
     * Merges another {@link PlayerDelta} into this instance
     *
     * @param otherDelta The other delta to absorb into this one
     * @throws NullPointerException If {@code otherDelta} is null
     */
    public void merge(final PlayerDelta otherDelta) throws NullPointerException {
        Objects.requireNonNull(otherDelta, "otherDelta cannot be null");
        if (this == otherDelta) {
            return;
        }

        otherDelta.saves.forEach((componentName, document) -> {
            final Long otherVersion = otherDelta.versions.get(componentName);
            final long versionToApply = otherVersion != null ? otherVersion : 1L;
            addSave(componentName, document, versionToApply);
        });

        otherDelta.removes.forEach(componentName -> {
            final Long otherVersion = otherDelta.versions.get(componentName);
            final long versionToApply = otherVersion != null ? otherVersion : 1L;
            addRemove(componentName, versionToApply);
        });
    }

    /**
     * Serializes this entire delta into a standardized binary {@link RawBsonDocument}
     * <p>
     * The resulting payload contains the root {@code uuid}, a {@code save} sub-document, a {@code remove} array, and a {@code versions} tracking map, structured for gRPC streaming
     * </p>
     *
     * @return A binary BSON document ready for network transmission
     */
    public RawBsonDocument toRawBsonDocument() {
        final var rootDocument = new BsonDocument(PlayerDataContract.FIELD_UUID, new BsonString(playerUuid.toString()));
        final var saveDocument = new BsonDocument(saves.size());
        final var versionsDocument = new BsonDocument(versions.size());
        final var removeArray = new BsonArray(removes.size());

        saves.forEach(saveDocument::append);
        removes.forEach(componentName -> removeArray.add(new BsonString(componentName)));
        versions.forEach((componentName, version) -> versionsDocument.append(componentName, new BsonInt64(version)));

        rootDocument.append(PlayerDataContract.FIELD_SAVE, saveDocument);
        rootDocument.append(PlayerDataContract.FIELD_REMOVE, removeArray);
        rootDocument.append(PlayerDataContract.FIELD_VERSIONS, versionsDocument);

        return BsonUtil.toRawBsonDocument(rootDocument);
    }

    /**
     * Checks whether this delta contains zero modifications
     *
     * @return {@code true} if there are no pending saves and no pending removals {@code false} otherwise
     */
    public boolean isEmpty() {
        return saves.isEmpty() && removes.isEmpty();
    }

    /**
     * Gets the unique identifier of the player for this delta
     *
     * @return The player {@link UUID}.
     */
    public UUID getPlayerUuid() {
        return playerUuid;
    }

    /**
     * Gets the timestamp when this delta was last modified
     *
     * @return The last modified timestamp
     */
    public long getLastModifiedTimestamp() {
        return lastModifiedTimestamp;
    }

    /**
     * Returns an unmodifiable map of all pending component saves in this delta
     *
     * @return A read-only map of component names to their updated BSON payloads
     */
    public Map<String, BsonDocument> getSaves() {
        return Collections.unmodifiableMap(saves);
    }

    /**
     * Returns an unmodifiable set of all pending component removing
     *
     * @return A read-only set of component namespaces to delete
     */
    public Set<String> getRemoves() {
        return Collections.unmodifiableSet(removes);
    }

    /**
     * Returns an unmodifiable map of the component version tracking.
     *
     * @return A read-only map of component names to their latest recorded version numbers.
     */
    public Map<String, Long> getVersions() {
        return Collections.unmodifiableMap(versions);
    }
}
