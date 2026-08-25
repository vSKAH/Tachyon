package tech.skworks.tachyon.plugin.core.playerdata;

import org.bson.BsonDocument;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tech.skworks.tachyon.api.component.ComponentCodec;
import tech.skworks.tachyon.api.component.ComponentNamespace;
import tech.skworks.tachyon.api.component.ComponentRegistry;
import tech.skworks.tachyon.api.profile.TachyonProfile;
import tech.skworks.tachyon.plugin.common.util.TachyonLogger;
import tech.skworks.tachyon.plugin.spigot.TachyonCore;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

public class TachyonProfileImpl implements TachyonProfile {

    private static final TachyonLogger LOGGER = TachyonCore.getModuleLogger("PlayerProfile");

    private final UUID uuid;
    private final ComponentRegistry registry;

    private final Map<Class<? extends Record>, Record> components = new ConcurrentHashMap<>();
    private final Set<Class<? extends Record>> deleted = ConcurrentHashMap.newKeySet();

    private final Map<Class<? extends Record>, Long> versions = new ConcurrentHashMap<>();
    private final Map<Class<? extends Record>, Long> sentVersions = new ConcurrentHashMap<>();

    public TachyonProfileImpl(UUID uuid, ComponentRegistry registry) {
        this.uuid = uuid;
        this.registry = registry;
    }

    public <T extends Record> void initComponent(@NotNull final T component) {
        components.put(component.getClass(), component);
        versions.put(component.getClass(), 1L);
        sentVersions.put(component.getClass(), 1L);
    }

    @Override
    public <T extends Record> void setComponent(@NotNull T component) {
        components.put(component.getClass(), component);
        versions.merge(component.getClass(), 1L, Long::sum);
    }

    @Override
    public <T extends Record> void updateComponent(@NotNull final Class<T> clazz, @NotNull final UnaryOperator<T> modifier) {
        Record updated = components.computeIfPresent(clazz, (k, current) -> {
            T typed = clazz.cast(current);
            return modifier.apply(typed);
        });
        if (updated != null) {
            versions.merge(clazz, 1L, Long::sum);
        }
    }

    @Override
    public <T extends Record> T getComponent(@NotNull final Class<T> clazz, @NotNull final T defaultValue) {
        T value = getComponent(clazz);
        if (value != null) {
            return value;
        }
        setComponent(defaultValue);
        return defaultValue;
    }

    @Override
    @SuppressWarnings("unchecked")
    public @Nullable <T extends Record> T getComponent(@NotNull final Class<T> clazz) {
        Record component = components.get(clazz);
        if (component == null) return null;
        if (clazz.isInstance(component)) {
            return (T) component;
        }
        return null;
    }

    @Override
    public <T extends Record> void removeComponent(@NotNull Class<T> clazz) {
        components.remove(clazz);
        versions.remove(clazz);
        sentVersions.remove(clazz);
        deleted.add(clazz);
    }

    @Override
    public boolean hasPendingChanges() {
        if (!deleted.isEmpty()) return true;
        for (Map.Entry<Class<? extends Record>, Long> entry : versions.entrySet()) {
            Long lastSent = sentVersions.get(entry.getKey());
            if (!Objects.equals(entry.getValue(), lastSent)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public @NotNull Map<ComponentNamespace, BsonDocument> extractDirtyComponents() {
        Map<ComponentNamespace, BsonDocument> dirtyMap = new HashMap<>();

        for (final Map.Entry<Class<? extends Record>, Long> entry : versions.entrySet()) {
            final Class<? extends Record> clazz = entry.getKey();
            final Long currentVersion = entry.getValue();
            final Long lastSentVersion = sentVersions.get(clazz);

            if (Objects.equals(currentVersion, lastSentVersion)) {
                continue;
            }

            final Record component = components.get(clazz);
            if (component == null) {
                versions.remove(clazz, currentVersion);
                continue;
            }

            ComponentCodec<?> codec = registry.getCodec(clazz);
            if (codec == null) {
                LOGGER.warn("'extractDirtyComponents' No codec found for component {} ", clazz.getName());
                continue;
            }

            BsonDocument encoded = encodeUnchecked(codec, component);
            dirtyMap.put(codec.getComponentNamespace(), encoded);
            sentVersions.put(clazz, currentVersion);
        }
        return dirtyMap;
    }

    @SuppressWarnings("unchecked")
    private <R extends Record> BsonDocument encodeUnchecked(ComponentCodec<R> codec, Record instance) {
        return codec.encode((R) instance);
    }

    @Override
    public @NotNull List<ComponentNamespace> extractDeletedComponents() {
        List<ComponentNamespace> list = new ArrayList<>();
        for (Class<? extends Record> clazz : deleted) {
            ComponentCodec<?> codec = registry.getCodec(clazz);
            if (codec == null) {
                LOGGER.warn("'extractDeletedComponents' No codec found for component {} ", clazz.getName());
                continue;
            }
            list.add(codec.getComponentNamespace());
        }
        return list;
    }

    @Override
    public void markAsClean(@NotNull final Collection<Class<? extends Record>> savedClasses, @NotNull final Collection<ComponentNamespace> deletedNames) {
        for (ComponentNamespace deletedName : deletedNames) {

            ComponentCodec<?> codec = registry.getCodec(deletedName);
            if (codec == null) {
                LOGGER.warn("'markAsClean' No codec found for component {} ", deletedName);
                continue;
            }

            deleted.remove(codec.getComponentClass());
        }

    }

    @Override
    public UUID getUuid() {
        return uuid;
    }
}
