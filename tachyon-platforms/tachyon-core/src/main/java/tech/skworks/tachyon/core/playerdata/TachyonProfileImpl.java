package tech.skworks.tachyon.core.playerdata;

import org.bson.BsonDocument;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.skworks.tachyon.api.component.ComponentCodec;
import tech.skworks.tachyon.api.component.ComponentNamespace;
import tech.skworks.tachyon.api.component.ComponentRegistry;
import tech.skworks.tachyon.api.profile.TachyonProfile;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.UnaryOperator;

public class TachyonProfileImpl implements TachyonProfile {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tachyon/Profile");

    private final UUID uuid;
    private final ComponentRegistry registry;

    private final Map<ComponentNamespace, Record> components = new ConcurrentHashMap<>();
    private final Set<ComponentNamespace> deleted = ConcurrentHashMap.newKeySet();

    private final Map<ComponentNamespace, Long> versions = new ConcurrentHashMap<>();
    private final Map<ComponentNamespace, Long> sentVersions = new ConcurrentHashMap<>();

    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Lock gameLock = rwLock.readLock();
    private final Lock extractLock = rwLock.writeLock();

    private AtomicBoolean freeze = new AtomicBoolean(false);

    public TachyonProfileImpl(final @NotNull UUID uuid, final @NotNull ComponentRegistry registry) {
        this.uuid = Objects.requireNonNull(uuid, "uuid cannot be null");
        this.registry = Objects.requireNonNull(registry, "registry cannot be null");
    }

    public void initVersion(@NotNull final ComponentNamespace namespace, final long version) {
        this.versions.put(namespace, version);
        this.sentVersions.put(namespace, version);
    }

    public <T extends Record> void initComponent(@NotNull final ComponentNamespace namespace, @NotNull final T component, final long version) {
        components.put(namespace, component);
        versions.put(namespace, version);
        sentVersions.put(namespace, version);
        deleted.remove(namespace);

    }

    public <T extends Record> void initComponent(@NotNull final ComponentNamespace namespace, @NotNull final T component) {
        initComponent(namespace, component, 1L);
    }

    @Override
    public <T extends Record> void setComponent(@NotNull final ComponentNamespace namespace, @NotNull T component) {
        gameLock.lock();
        try {
            if (freeze.get()) {
                LOGGER.warn("Attempted to modify frozen profile {} (Component: {})", uuid, namespace);
                return;
            }
            components.put(namespace, component);
            versions.merge(namespace, 1L, Long::sum);
            deleted.remove(namespace);
        } finally {
            gameLock.unlock();
        }
    }

    @Override
    public <T extends Record> void updateComponent(@NotNull final ComponentNamespace namespace, @NotNull final Class<T> clazz, @NotNull final UnaryOperator<T> modifier) {
        gameLock.lock();
        try {
            if (freeze.get()) {
                LOGGER.warn("Attempted to modify frozen profile {} (Component: {})", uuid, namespace);
                return;
            }
            Record updated = components.computeIfPresent(namespace, (_, current) -> modifier.apply(clazz.cast(current)));
            if (updated != null) {
                versions.merge(namespace, 1L, Long::sum);
            }
        } finally {
            gameLock.unlock();
        }
    }

    @Override
    public <T extends Record> T getComponent(@NotNull final ComponentNamespace namespace, @NotNull final T defaultValue) {
        gameLock.lock();
        try {
            Record component = components.get(namespace);
            if (component != null) {
                return (T) component;
            }
            if (freeze.get()) {
                LOGGER.warn("Attempted to write default to frozen profile {} (Component: {})", uuid, namespace);
                return defaultValue;
            }
            components.put(namespace, defaultValue);
            versions.merge(namespace, 1L, Long::sum);
            deleted.remove(namespace);
            return defaultValue;
        } finally {
            gameLock.unlock();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public @Nullable <T extends Record> T getComponent(@NotNull final ComponentNamespace namespace) {
        gameLock.lock();
        try {
            Record component = components.get(namespace);
            if (component == null) return null;
            return (T) component;
        } finally {
            gameLock.unlock();
        }
    }

    @Override
    public void removeComponent(@NotNull final ComponentNamespace namespace) {
        gameLock.lock();
        try {
            if (freeze.get()) {
                LOGGER.warn("Attempted to modify frozen profile {} (Component: {})", uuid, namespace);
                return;
            }
            components.remove(namespace);
            versions.merge(namespace, 1L, Long::sum);
            deleted.add(namespace);
        } finally {
            gameLock.unlock();
        }
    }

    public void freeze() {
        extractLock.lock();
        try {
            this.freeze.set(true);
        } finally {
            extractLock.unlock();
        }
    }

    public boolean isFrozen() {
        return freeze.get();
    }

    @Override
    public boolean hasPendingChanges() {
        extractLock.lock();
        try {
            if (!deleted.isEmpty()) return true;
            for (final var entry : versions.entrySet()) {
                final var lastSent = sentVersions.get(entry.getKey());
                if (!Objects.equals(entry.getValue(), lastSent)) {
                    return true;
                }
            }
            return false;
        } finally {
            extractLock.unlock();
        }
    }

    @Override
    public @NotNull Map<ComponentNamespace, BsonDocument> extractDirtyComponents() {
        extractLock.lock();
        try {
            Map<ComponentNamespace, BsonDocument> dirtyMap = new HashMap<>();

            for (final Map.Entry<ComponentNamespace, Long> entry : versions.entrySet()) {
                final var componentNamespace = entry.getKey();
                final var currentVersion = entry.getValue();
                final var lastSentVersion = sentVersions.get(componentNamespace);

                if (Objects.equals(currentVersion, lastSentVersion)) {
                    continue;
                }

                final Record component = components.get(componentNamespace);
                if (component == null) {
                    continue;
                }

                ComponentCodec<?> codec = registry.getCodec(componentNamespace);
                if (codec == null) {
                    LOGGER.warn("'extractDirtyComponents' No codec found for component {} ", componentNamespace);
                    continue;
                }

                BsonDocument encoded = encodeUnchecked(codec, component);
                dirtyMap.put(componentNamespace, encoded);
            }
            return dirtyMap;
        } finally {
            extractLock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    private <R extends Record> BsonDocument encodeUnchecked(ComponentCodec<R> codec, Record instance) {
        return codec.encode((R) instance);
    }

    @Override
    public @NotNull List<ComponentNamespace> extractDeletedComponents() {
        extractLock.lock();
        try {
            final var toDelete = new ArrayList<ComponentNamespace>(deleted.size());
            for (final var namespace : deleted) {
                if (components.containsKey(namespace)) {
                    continue;
                }

                final var codec = registry.getCodec(namespace);
                if (codec != null) toDelete.add(codec.getComponentNamespace());
            }
            return toDelete;
        } finally {
            extractLock.unlock();
        }
    }

    @Override
    public void markAsClean(@NotNull final Map<ComponentNamespace, Long> confirmedVersions, @NotNull final Collection<ComponentNamespace> deletedNames) {
        extractLock.lock();
        try {
            for (final var confirmedVersionsEntry : confirmedVersions.entrySet()) {
                final var namespace = confirmedVersionsEntry.getKey();
                final var codec = registry.getCodec(namespace);
                if (codec != null) {
                    final var versionSent = confirmedVersionsEntry.getValue();
                    sentVersions.compute(namespace, (_, currentSent) -> currentSent == null ? versionSent : Math.max(currentSent, versionSent));
                }
            }

            for (final var componentNamespace : deletedNames) {
                final var codec = registry.getCodec(componentNamespace);
                if (codec != null) {
                    if (!components.containsKey(componentNamespace)) {
                        deleted.remove(componentNamespace);
                    }
                }
            }
        } finally {
            extractLock.unlock();
        }
    }

    @Override
    public UUID getUuid() {
        return uuid;
    }

    public Long getVersion(@NotNull final ComponentNamespace namespace) {
        gameLock.lock();
        try {
            ComponentCodec<?> codec = registry.getCodec(namespace);
            return codec != null ? versions.getOrDefault(namespace, 1L) : 1L;
        } finally {
            gameLock.unlock();
        }
    }
}
