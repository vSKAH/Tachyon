package tech.skworks.tachyon.plugin.core.playerdata;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tech.skworks.tachyon.api.profile.TachyonProfile;
import tech.skworks.tachyon.libs.com.google.protobuf.Message;
import tech.skworks.tachyon.libs.com.google.protobuf.Parser;
import tech.skworks.tachyon.plugin.core.component.ComponentRegistryImpl;
import tech.skworks.tachyon.plugin.spigot.TachyonCore;
import tech.skworks.tachyon.plugin.common.util.TachyonLogger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class TachyonProfileImpl implements TachyonProfile {

    private static final TachyonLogger LOGGER = TachyonCore.getModuleLogger("PlayerProfile");

    private final UUID uuid;

    private final Map<String, Message> components = new ConcurrentHashMap<>();
    private final Map<String, Long> versions = new ConcurrentHashMap<>();
    private final Map<String, Long> sentVersions = new ConcurrentHashMap<>();
    private final Set<String> deleted = ConcurrentHashMap.newKeySet();

    public TachyonProfileImpl(UUID uuid) {
        this.uuid = uuid;
    }

    public <T extends Message> void initComponent(@NotNull final T component) {
        components.put(component.getClass().getName(), component);
    }

    @Override
    public <T extends Message> void setComponent(@NotNull final T component) {
        components.put(component.getClass().getName(), component);
        versions.merge(component.getClass().getName(), 1L, Long::sum);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Message, B extends Message.Builder> void updateComponent(@NotNull final Class<T> clazz, @NotNull final Consumer<B> modifier) {
        Message updated = components.computeIfPresent(clazz.getName(), (k, current) -> {
            Message migrated = current;
            if (current.getClass() != clazz) {
                try {
                    final Parser<? extends Message> parser = ComponentRegistryImpl.getParserByClassName(clazz.getName());
                    if (parser != null) {
                        migrated = parser.parseFrom(current.toByteArray());
                    }
                } catch (Exception e) {
                    LOGGER.error(e, "Failed to migrate component {} during updateComponent", clazz.getName());
                }
            }
            B builder = (B) migrated.toBuilder();
            modifier.accept(builder);
            return builder.build();
        });
        if (updated != null) {
            versions.merge(clazz.getName(), 1L, Long::sum);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public @Nullable <T extends Message> T getComponent(@NotNull final Class<T> clazz) {
        final Message component = components.get(clazz.getName());
        if (component == null) {
            return null;
        }
        if (component.getClass() == clazz) {
            return (T) component;
        }
        try {
            final Parser<? extends Message> parser = ComponentRegistryImpl.getParserByClassName(clazz.getName());
            if (parser != null) {
                final Message newInstance = parser.parseFrom(component.toByteArray());
                components.put(clazz.getName(), newInstance);
                return (T) newInstance;
            }
        } catch (Exception e) {
            LOGGER.error(e, "Failed to migrate component {} to new classloader", clazz.getName());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public @Nullable <T extends Message> T getComponent(@NotNull final String componentShortName) {
        Collection<Message> copy = List.copyOf(components.values());
        return (T) copy.stream().filter(component -> component.getDescriptorForType().getName().equals(componentShortName)).findFirst().orElse(null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Message> T getComponent(@NotNull final Class<T> clazz, @NotNull final T defaultValue) {
        T value = getComponent(clazz);
        if (value != null) {
            return value;
        }
        setComponent(defaultValue);
        return defaultValue;
    }

    @Override
    public <T extends Message> void removeComponent(@NotNull final T componentDefaultInstance) {
        components.remove(componentDefaultInstance.getClass().getName());
        versions.remove(componentDefaultInstance.getClass().getName());
        deleted.add(componentDefaultInstance.getDescriptorForType().getFullName());
    }

    @Override
    public boolean hasPendingChanges() {
        return !versions.isEmpty() || !deleted.isEmpty();
    }

    @Override
    public @NotNull List<Message> extractDirtyComponents() {
        final List<Message> result = new ArrayList<>();
        for (final Map.Entry<String, Long> entry : versions.entrySet()) {
            final String className = entry.getKey();
            final Long version = entry.getValue();
            final Message component = components.get(className);
            if (component == null) {
                versions.remove(className, version);
                continue;
            }
            sentVersions.put(className, version);
            result.add(component);
        }
        return result;
    }

    @Override
    public @NotNull List<String> extractDeletedComponentsUrls() {
        return List.copyOf(deleted);
    }

    @Override
    public void markAsClean(@NotNull final Collection<Class<? extends Message>> savedClasses, @NotNull final Collection<String> deletedComponent) {
        for (final Class<? extends Message> clazz : savedClasses) {
            final Long sentVersion = sentVersions.get(clazz.getName());
            if (sentVersion == null) continue;
            versions.remove(clazz.getName(), sentVersion);
        }
        deletedComponent.forEach(deleted::remove);
    }

    @Override
    public UUID getUuid() {
        return uuid;
    }
}
