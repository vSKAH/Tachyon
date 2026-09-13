package tech.skworks.tachyon.plugin.core.playerdata;

import org.bson.BsonDocument;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tech.skworks.tachyon.api.component.ComponentCodec;
import tech.skworks.tachyon.api.component.ComponentNamespace;
import tech.skworks.tachyon.api.component.ComponentRegistry;
import tech.skworks.tachyon.api.profile.TachyonProfile;
import tech.skworks.tachyon.plugin.core.util.TachyonLogger;
import tech.skworks.tachyon.plugin.spigot.TachyonCore;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

public class TachyonProfileImpl implements TachyonProfile {

    private static final TachyonLogger LOGGER = TachyonCore.getModuleLogger("PlayerProfile");

    private final UUID uuid;
    private final ComponentRegistry registry;

    private final Map<ComponentNamespace, Record> components = new ConcurrentHashMap<>();
    private final Set<ComponentNamespace> deleted = ConcurrentHashMap.newKeySet();

    private final Map<ComponentNamespace, Long> versions = new ConcurrentHashMap<>();
    private final Map<ComponentNamespace, Long> sentVersions = new ConcurrentHashMap<>();

    public TachyonProfileImpl(UUID uuid, ComponentRegistry registry) {
        this.uuid = uuid;
        this.registry = registry;
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
        components.put(namespace, component);
        versions.merge(namespace, 1L, Long::sum);
        deleted.remove(namespace);
    }

    @Override
    public <T extends Record> void updateComponent(@NotNull final ComponentNamespace componentNamespace, @NotNull final Class<T> clazz, @NotNull final UnaryOperator<T> modifier) {
        Record updated = components.computeIfPresent(componentNamespace, (_, current) -> modifier.apply(clazz.cast(current)));
        if (updated != null) {
            versions.merge(componentNamespace, 1L, Long::sum);
        }
    }

    @Override
    public <T extends Record> T getComponent(@NotNull final ComponentNamespace componentNamespace, @NotNull final T defaultValue) {
        T value = getComponent(componentNamespace);
        if (value != null) {
            return value;
        }
        setComponent(componentNamespace, defaultValue);
        return defaultValue;
    }

    @Override
    @SuppressWarnings("unchecked")
    public @Nullable <T extends Record> T getComponent(@NotNull final ComponentNamespace componentNamespace) {
        Record component = components.get(componentNamespace);
        if (component == null) return null;
        return (T) component;
    }

    @Override
    public void removeComponent(@NotNull final ComponentNamespace componentNamespace) {
        components.remove(componentNamespace);
        versions.merge(componentNamespace, 1L, Long::sum);
        deleted.add(componentNamespace);
    }

    @Override
    public boolean hasPendingChanges() {
        if (!deleted.isEmpty()) return true;
        for (final var entry : versions.entrySet()) {
            final var lastSent = sentVersions.get(entry.getKey());
            if (!Objects.equals(entry.getValue(), lastSent)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public @NotNull Map<ComponentNamespace, BsonDocument> extractDirtyComponents() {
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
                //versions.remove(clazz, currentVersion);
                continue;
            }

            ComponentCodec<?> codec = registry.getCodec(componentNamespace);
            if (codec == null) {
                LOGGER.warn("'extractDirtyComponents' No codec found for component {} ", componentNamespace);
                continue;
            }

            BsonDocument encoded = encodeUnchecked(codec, component);
            dirtyMap.put(componentNamespace, encoded);
          //  sentVersions.put(componentNamespace, currentVersion);
        }
        return dirtyMap;
    }

    @SuppressWarnings("unchecked")
    private <R extends Record> BsonDocument encodeUnchecked(ComponentCodec<R> codec, Record instance) {
        return codec.encode((R) instance);
    }

    @Override
    public @NotNull List<ComponentNamespace> extractDeletedComponents() {
        final var toDelete = new ArrayList<ComponentNamespace>(deleted.size());
        for (final var namespace : deleted) {
            if (components.containsKey(namespace)) {
                continue;
            }

            final var codec = registry.getCodec(namespace);
            if (codec != null) toDelete.add(codec.getComponentNamespace());
        }
        return toDelete;
    }

    @Override
    public void markAsClean(@NotNull final Map<ComponentNamespace, Long> confirmedVersions, @NotNull final Collection<ComponentNamespace> deletedNames) {

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

    }

    @Override
    public UUID getUuid() {
        return uuid;
    }

    public Long getVersion(@NotNull final ComponentNamespace namespace) {
        ComponentCodec<?> codec = registry.getCodec(namespace);
        return codec != null ? versions.getOrDefault(namespace, 1L) : 1L;
    }
}
