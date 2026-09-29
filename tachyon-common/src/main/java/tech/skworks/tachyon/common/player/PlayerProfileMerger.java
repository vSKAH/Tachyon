package tech.skworks.tachyon.common.player;

import org.bson.*;
import tech.skworks.tachyon.common.BsonUtil;
import tech.skworks.tachyon.common.contract.PlayerDataContract;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The engine responsible for merging player data and resolving version conflicts
 * <p>
 * This utility is stateless and shared between Platforms and Quarkus
 * Whenever a player makes changes (like buying items or switching servers), this class combines
 * updates into the player profile to making older data never overwrites newer data
 * </p>
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @author Jimmy (vSKAH) - 21/09/2026
 * @version 1.0
 * @since 2.0.0-alpha.2
 */
public final class PlayerProfileMerger {

    private PlayerProfileMerger() {
    }

    /**
     * Checks if an incoming change is outdated compared to what we already have
     * <p>
     * If the incoming version is smaller than or equal to the current version, it is considered "stale" and should be ignored
     * </p>
     *
     * @param incomingVersion The version number of the new update (can be null).
     * @param currentVersion  The version number currently saved in memory or database (can be null).
     * @return {@code true} if the update is outdated and should be skipped.
     */
    public static boolean isStale(final Long incomingVersion, final Long currentVersion) {
        return incomingVersion != null && currentVersion != null && incomingVersion <= currentVersion;
    }

    /**
     * Takes a player full profile and applies it a batch of changes (delta)
     * <p>
     * This method
     * <ol>
     *   <li>Copies over existing components and their versions from the base profile</li>
     *   <li>Applies newly saved or updated components from the delta (skipping stale versions)</li>
     *   <li>Removes any components marked for deletion in the delta</li>
     *   <li>Returns a brand new, fully merged BSON document ready for Redis cache or database storage</li>
     * </ol>
     * </p>
     *
     * @param baseProfileDocument   The player full profile document (usually loaded from Redis or MongoDB)
     * @param incomingDeltaDocument The changes to apply (containing {@code save}, {@code remove}, and {@code versions})
     * @return A complete, up-to-date {@link RawBsonDocument} with all changes applied
     * @throws NullPointerException If either argument is null
     */
    public static RawBsonDocument mergeDeltaIntoProfile(final RawBsonDocument baseProfileDocument,
                                                        final BsonDocument incomingDeltaDocument) throws NullPointerException {
        Objects.requireNonNull(baseProfileDocument, "baseProfileDocument cannot be null");
        Objects.requireNonNull(incomingDeltaDocument, "incomingDeltaDocument cannot be null");

        final var uuidField = PlayerDataContract.FIELD_UUID;
        final var componentsField = PlayerDataContract.FIELD_COMPONENTS;
        final var versionsField = PlayerDataContract.FIELD_VERSIONS;
        final var saveField = PlayerDataContract.FIELD_SAVE;
        final var removeField = PlayerDataContract.FIELD_REMOVE;

        final var finalComponentsDocument = new BsonDocument();
        if (baseProfileDocument.containsKey(componentsField) && baseProfileDocument.isDocument(componentsField)) {
            finalComponentsDocument.putAll(baseProfileDocument.getDocument(componentsField));
        }

        final var finalVersionsDocument = new BsonDocument();
        if (baseProfileDocument.containsKey(versionsField) && baseProfileDocument.isDocument(versionsField)) {
            finalVersionsDocument.putAll(baseProfileDocument.getDocument(versionsField));
        }

        final var incomingVersionsDocument = incomingDeltaDocument.containsKey(versionsField) && incomingDeltaDocument.isDocument(versionsField) ?
                incomingDeltaDocument.getDocument(versionsField) : new BsonDocument();

        if (incomingDeltaDocument.containsKey(saveField) && incomingDeltaDocument.isDocument(saveField)) {
            final var savesDocument = incomingDeltaDocument.getDocument(saveField);
            for (final var componentName : savesDocument.keySet()) {
                final Long incomingVersion = incomingVersionsDocument.containsKey(componentName) ? incomingVersionsDocument.getNumber(componentName).longValue() : null;
                final Long currentVersion = finalVersionsDocument.containsKey(componentName) ? finalVersionsDocument.getNumber(componentName).longValue() : null;

                if (isStale(incomingVersion, currentVersion)) continue;

                finalComponentsDocument.put(componentName, savesDocument.get(componentName));
                if (incomingVersion != null) finalVersionsDocument.put(componentName, new BsonInt64(incomingVersion));
            }
        }

        if (incomingDeltaDocument.containsKey(removeField) && incomingDeltaDocument.isArray(removeField)) {
            final var removesArray = incomingDeltaDocument.getArray(removeField);
            for (final var arrayElement : removesArray) {
                if (!arrayElement.isString()) continue;

                final var componentName = arrayElement.asString().getValue();
                final Long incomingVersion = incomingVersionsDocument.containsKey(componentName) ? incomingVersionsDocument.getNumber(componentName).longValue() : null;
                final Long currentVersion = finalVersionsDocument.containsKey(componentName) ? finalVersionsDocument.getNumber(componentName).longValue() : null;

                if (isStale(incomingVersion, currentVersion)) continue;

                finalComponentsDocument.remove(componentName);
                if (incomingVersion != null) finalVersionsDocument.put(componentName, new BsonInt64(incomingVersion));
            }
        }

        final var playerUuid = baseProfileDocument.containsKey(uuidField) && baseProfileDocument.isString(uuidField) ?
                baseProfileDocument.getString(uuidField).getValue() : incomingDeltaDocument.getString(uuidField).getValue();

        final var mergedProfileDocument = new BsonDocument(3)
                .append(uuidField, new BsonString(playerUuid))
                .append(componentsField, finalComponentsDocument)
                .append(versionsField, finalVersionsDocument);

        return BsonUtil.toRawBsonDocument(mergedProfileDocument);
    }

    /**
     * Combines two deltas together into one document
     * <p>
     * This is useful when a player has multiple rapid updates waiting in memory, allowing them
     * to be bundled together into a single database write while keeping version tracking accurate
     * </p>
     *
     * @param targetDeltaDocument   The main delta document that will receive the new changes
     * @param incomingDeltaDocument The new changes to merge into the target
     * @throws NullPointerException If either document is null
     */
    public static void mergeDeltaIntoDelta(final BsonDocument targetDeltaDocument,
                                           final BsonDocument incomingDeltaDocument) throws NullPointerException {
        Objects.requireNonNull(targetDeltaDocument, "targetDeltaDocument cannot be null");
        Objects.requireNonNull(incomingDeltaDocument, "incomingDeltaDocument cannot be null");

        final var versionsField = PlayerDataContract.FIELD_VERSIONS;
        final var saveField = PlayerDataContract.FIELD_SAVE;
        final var removeField = PlayerDataContract.FIELD_REMOVE;

        final var targetVersions = targetDeltaDocument.containsKey(versionsField) && targetDeltaDocument.isDocument(versionsField) ?
                targetDeltaDocument.getDocument(versionsField) : new BsonDocument();
        targetDeltaDocument.put(versionsField, targetVersions);

        final var targetSaves = targetDeltaDocument.containsKey(saveField) && targetDeltaDocument.isDocument(saveField) ?
                targetDeltaDocument.getDocument(saveField) : new BsonDocument();
        targetDeltaDocument.put(saveField, targetSaves);

        final var targetRemoves = targetDeltaDocument.containsKey(removeField) && targetDeltaDocument.isArray(removeField) ?
                targetDeltaDocument.getArray(removeField) : new BsonArray();
        targetDeltaDocument.put(removeField, targetRemoves);

        final var incomingVersions = incomingDeltaDocument.containsKey(versionsField) && incomingDeltaDocument.isDocument(versionsField) ?
                incomingDeltaDocument.getDocument(versionsField) : new BsonDocument();

        if (incomingDeltaDocument.containsKey(saveField) && incomingDeltaDocument.isDocument(saveField)) {
            final var incomingSaves = incomingDeltaDocument.getDocument(saveField);
            for (final var componentName : incomingSaves.keySet()) {
                final Long incomingVersion = incomingVersions.containsKey(componentName) ? incomingVersions.getNumber(componentName).longValue() : null;
                final Long currentVersion = targetVersions.containsKey(componentName) ? targetVersions.getNumber(componentName).longValue() : null;

                if (isStale(incomingVersion, currentVersion)) {
                    continue;
                }

                targetSaves.put(componentName, incomingSaves.get(componentName));
                targetRemoves.remove(new BsonString(componentName));
                if (incomingVersion != null) {
                    targetVersions.put(componentName, new BsonInt64(incomingVersion));
                }
            }
        }

        if (incomingDeltaDocument.containsKey(removeField) && incomingDeltaDocument.isArray(removeField)) {
            final var incomingRemoves = incomingDeltaDocument.getArray(removeField);
            for (final var arrayElement : incomingRemoves) {
                if (!arrayElement.isString()) {
                    continue;
                }
                final var componentName = arrayElement.asString().getValue();
                final Long incomingVersion = incomingVersions.containsKey(componentName) ? incomingVersions.getNumber(componentName).longValue() : null;
                final Long currentVersion = targetVersions.containsKey(componentName) ? targetVersions.getNumber(componentName).longValue() : null;

                if (isStale(incomingVersion, currentVersion)) {
                    continue;
                }

                targetSaves.remove(componentName);
                if (!targetRemoves.contains(arrayElement)) {
                    targetRemoves.add(arrayElement);
                }
                if (incomingVersion != null) {
                    targetVersions.put(componentName, new BsonInt64(incomingVersion));
                }
            }
        }
    }

    /**
     * Reads all component version numbers from a BSON document
     *
     * @param document The document to read versions from
     * @return A map of component names to their version numbers (or an empty map)
     */
    public static Map<String, Long> extractVersions(final BsonDocument document) {
        final var versionsField = PlayerDataContract.FIELD_VERSIONS;
        if (document == null || !document.containsKey(versionsField) || !document.isDocument(versionsField)) {
            return Map.of();
        }
        final var versionsDocument = document.getDocument(versionsField);
        final var versionsMap = new HashMap<String, Long>(versionsDocument.size());
        for (final var componentName : versionsDocument.keySet()) {
            versionsMap.put(componentName, versionsDocument.getNumber(componentName).longValue());
        }
        return Map.copyOf(versionsMap);
    }
}
