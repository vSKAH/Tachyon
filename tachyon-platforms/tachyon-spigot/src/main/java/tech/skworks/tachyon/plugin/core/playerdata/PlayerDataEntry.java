package tech.skworks.tachyon.plugin.core.playerdata;

import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.bson.RawBsonDocument;
import org.jetbrains.annotations.NotNull;
import tech.skworks.tachyon.api.component.ComponentNamespace;
import tech.skworks.tachyon.api.profile.TachyonProfile;
import tech.skworks.tachyon.common.BsonUtil;
import tech.skworks.tachyon.common.contract.PlayerDataContract;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Short class explaination
 * <p>
 * Long class explaination with @link if needed
 * </p>
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @author Jimmy (vSKAH) - 12/09/2026
 * @version 1.0
 * @since 2.0.0-alpha.2
 */
public class PlayerDataEntry {

    private @NotNull final UUID uuid;
    private @NotNull final Map<ComponentNamespace, BsonDocument> saves;
    private @NotNull final Set<ComponentNamespace> removes;
    private @NotNull final Map<ComponentNamespace, Long> versions;

    private @NotNull final ReentrantLock lock;
    private volatile long lastEdit;

    public PlayerDataEntry(@NotNull final UUID uuid) {
        this.uuid = Objects.requireNonNull(uuid, "uuid cannot be null");
        this.saves = new ConcurrentHashMap<>();
        this.removes = ConcurrentHashMap.newKeySet();
        this.versions = new ConcurrentHashMap<>();
        this.lock = new ReentrantLock();
        this.lastEdit = System.currentTimeMillis();
    }

    public static @NotNull PlayerDataEntry fromProfile(@NotNull final TachyonProfile profile) {
        Objects.requireNonNull(profile, "profile cannot be null");
        final var entry = new PlayerDataEntry(profile.getUuid());

        profile.extractDirtyComponents().forEach((namespace, document) ->
                entry.addSave(namespace, document, profile.getVersion(namespace))
        );

        profile.extractDeletedComponents().forEach(namespace ->
                entry.addRemove(namespace, profile.getVersion(namespace))
        );

        return entry;
    }

    public void addSave(@NotNull final ComponentNamespace namespace, @NotNull final BsonDocument doc, final long version) {
        Objects.requireNonNull(namespace, "namespace cannot be null");
        Objects.requireNonNull(doc, "doc cannot be null");

        this.lock.lock();
        try {
            if (version >= versions.getOrDefault(namespace, 0L)) {
                saves.put(namespace, doc);
                removes.remove(namespace);
                versions.put(namespace, version);
                this.lastEdit = System.currentTimeMillis();
            }
        } finally {
            this.lock.unlock();
        }
    }

    public void addRemove(@NotNull final ComponentNamespace namespace, final long version) {
        Objects.requireNonNull(namespace, "namespace cannot be null");

        this.lock.lock();
        try {
            if (version >= versions.getOrDefault(namespace, 0L)) {
                removes.add(namespace);
                saves.remove(namespace);
                versions.put(namespace, version);
                this.lastEdit = System.currentTimeMillis();
            }
        } finally {
            this.lock.unlock();
        }
    }

    public void merge(@NotNull final PlayerDataEntry other) {
        Objects.requireNonNull(other, "other cannot be null");
        if (this == other) {
            return;
        }

        final Map<ComponentNamespace, BsonDocument> otherSaves;
        final Set<ComponentNamespace> otherRemoves;
        final Map<ComponentNamespace, Long> otherVersions;

        other.lock.lock();
        try {
            otherSaves = Map.copyOf(other.saves);
            otherRemoves = Set.copyOf(other.removes);
            otherVersions = Map.copyOf(other.versions);
        } finally {
            other.lock.unlock();
        }

        this.lock.lock();
        try {
            otherSaves.forEach((namespace, document) -> {
                final long otherVersion = otherVersions.getOrDefault(namespace, 1L);
                if (otherVersion >= this.versions.getOrDefault(namespace, 0L)) {
                    this.saves.put(namespace, document);
                    this.removes.remove(namespace);
                    this.versions.put(namespace, otherVersion);
                }
            });

            otherRemoves.forEach(namespace -> {
                final long otherVersion = otherVersions.getOrDefault(namespace, 1L);
                if (otherVersion >= this.versions.getOrDefault(namespace, 0L)) {
                    this.removes.add(namespace);
                    this.saves.remove(namespace);
                    this.versions.put(namespace, otherVersion);
                }
            });
            this.lastEdit = System.currentTimeMillis();
        } finally {
            this.lock.unlock();
        }
    }

    public void merge(@NotNull final BsonDocument other) {
        Objects.requireNonNull(other, "other cannot be null");

        final var versionsField = PlayerDataContract.FIELD_VERSIONS;
        final var saveField = PlayerDataContract.FIELD_SAVE;
        final var removeField = PlayerDataContract.FIELD_REMOVE;

        final var otherVersions = other.containsKey(versionsField) ? other.getDocument(versionsField) : new BsonDocument(0);
        final var otherSaves = other.containsKey(saveField) ? other.getDocument(saveField) : null;
        final var otherRemoves = other.containsKey(removeField) ? other.getArray(removeField) : null;

        this.lock.lock();
        try {
            if (otherSaves != null) {
                for (final var namespaceKey : otherSaves.keySet()) {
                    final var componentNamespace = ComponentNamespace.parse(namespaceKey);
                    final var otherVersion = otherVersions.containsKey(namespaceKey) ? otherVersions.getNumber(namespaceKey).longValue() : 1L;
                    final var currentVersion = versions.getOrDefault(componentNamespace, 0L);

                    if (otherVersion >= currentVersion) {
                        saves.put(componentNamespace, otherSaves.getDocument(namespaceKey));
                        removes.remove(componentNamespace);
                        versions.put(componentNamespace, otherVersion);
                    }
                }
            }

            if (otherRemoves != null) {
                for (final var key : otherRemoves) {
                    final var namespaceKey = key.asString().getValue();
                    final var componentNamespace = ComponentNamespace.parse(namespaceKey);
                    final var otherVersion = otherVersions.containsKey(namespaceKey) ? otherVersions.getNumber(namespaceKey).longValue() : 1L;
                    final var currentVersion = versions.getOrDefault(componentNamespace, 0L);

                    if (otherVersion >= currentVersion) {
                        removes.add(componentNamespace);
                        saves.remove(componentNamespace);
                        versions.put(componentNamespace, otherVersion);
                    }
                }
            }
            this.lastEdit = System.currentTimeMillis();
        } finally {
            this.lock.unlock();
        }
    }

    public @NotNull RawBsonDocument build() {
        this.lock.lock();
        try {
            final var rootDocument = new BsonDocument(PlayerDataContract.FIELD_UUID, new BsonString(uuid.toString()));
            final var saveDocument = new BsonDocument(saves.size());
            final var versionsDocument = new BsonDocument(versions.size());
            final var removeArray = new BsonArray(removes.size());

            saves.forEach((namespace, bsonDocument) -> saveDocument.append(namespace.fullNamespace(), bsonDocument));
            removes.forEach(namespace -> removeArray.add(new BsonString(namespace.fullNamespace())));
            versions.forEach((namespace, version) -> versionsDocument.append(namespace.fullNamespace(), new BsonInt64(version)));

            rootDocument.append(PlayerDataContract.FIELD_SAVE, saveDocument);
            rootDocument.append(PlayerDataContract.FIELD_REMOVE, removeArray);
            rootDocument.append(PlayerDataContract.FIELD_VERSIONS, versionsDocument);

            return BsonUtil.toRawBsonDocument(rootDocument);
        } finally {
            this.lock.unlock();
        }
    }

    public boolean isEmpty() {
        this.lock.lock();
        try {
            return saves.isEmpty() && removes.isEmpty();
        } finally {
            this.lock.unlock();
        }
    }

    public @NotNull UUID getUuid() {
        return uuid;
    }

    public long getLastEdit() {
        return lastEdit;
    }

    public @NotNull Map<ComponentNamespace, Long> getVersions() {
        this.lock.lock();
        try {
            return Map.copyOf(versions);
        } finally {
            this.lock.unlock();
        }
    }
    public @NotNull Set<ComponentNamespace> getRemoves() {
        this.lock.lock();
        try {
            return Set.copyOf(removes);
        } finally {
            this.lock.unlock();
        }
    }
}
