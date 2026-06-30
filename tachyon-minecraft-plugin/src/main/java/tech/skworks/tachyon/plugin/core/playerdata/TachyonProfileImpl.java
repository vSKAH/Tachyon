package tech.skworks.tachyon.plugin.core.playerdata;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tech.skworks.tachyon.api.profile.TachyonProfile;
import tech.skworks.tachyon.libs.com.google.protobuf.Message;
import tech.skworks.tachyon.plugin.spigot.TachyonCore;
import tech.skworks.tachyon.plugin.common.util.TachyonLogger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class TachyonProfileImpl implements TachyonProfile {

    private static final TachyonLogger LOGGER = TachyonCore.getModuleLogger("PlayerProfile");

    private final UUID uuid;

    private final Map<Class<? extends Message>, Message> components = new ConcurrentHashMap<>();
    private final Map<Class<?>, Long> versions = new ConcurrentHashMap<>();
    private final Map<Class<?>, Long> sentVersions = new ConcurrentHashMap<>();
    private final Set<String> deleted = ConcurrentHashMap.newKeySet();

    public TachyonProfileImpl(UUID uuid) {
        this.uuid = uuid;
    }

    public <T extends Message> void initComponent(@NotNull final T component) {
        components.put(component.getClass(), component);
    }

    @Override
    public <T extends Message> void setComponent(@NotNull final T component) {
        components.put(component.getClass(), component);
        versions.merge(component.getClass(), 1L, Long::sum);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Message, B extends Message.Builder> void updateComponent(@NotNull final Class<T> clazz, @NotNull final Consumer<B> modifier) {
        Message updated = components.computeIfPresent(clazz, (k, current) -> {
            B builder = (B) current.toBuilder();
            modifier.accept(builder);
            return builder.build();
        });
        if (updated != null) {
            versions.merge(clazz, 1L, Long::sum);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public @Nullable <T extends Message> T getComponent(@NotNull final Class<T> clazz) {
        return (T) components.get(clazz);
    }

    @SuppressWarnings("unchecked")
    public @Nullable <T extends Message> T getComponent(@NotNull final String componentShortName) {
        Collection<Message> copy = List.copyOf(components.values());
        return (T) copy.stream().filter(component -> component.getDescriptorForType().getName().equals(componentShortName)).findFirst().orElse(null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Message> T getComponent(@NotNull final Class<T> clazz, @NotNull final T defaultValue) {
        if (!components.containsKey(clazz)) {
            setComponent(defaultValue);
            return defaultValue;
        }
        return (T) components.get(clazz);
    }

    @Override
    public <T extends Message> void removeComponent(@NotNull final T componentDefaultInstance) {
        components.remove(componentDefaultInstance.getClass());
        versions.remove(componentDefaultInstance.getClass());
        deleted.add(componentDefaultInstance.getDescriptorForType().getFullName());
    }

    @Override
    public boolean hasPendingChanges() {
        return !versions.isEmpty() || !deleted.isEmpty();
    }

    @Override
    public @NotNull List<Message> extractDirtyComponents() {
        final List<Message> result = new ArrayList<>();
        for (final Map.Entry<Class<?>, Long> entry : versions.entrySet()) {
            final Class<?> clazz = entry.getKey();
            final Long version = entry.getValue();
            final Message component = components.get(clazz);
            if (component == null) {
                versions.remove(clazz, version);
                continue;
            }
            sentVersions.put(clazz, version);
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
            final Long sentVersion = sentVersions.get(clazz);
            if (sentVersion == null) continue;
            versions.remove(clazz, sentVersion);
        }
        deletedComponent.forEach(deleted::remove);
    }

    @Override
    public UUID getUuid() {
        return uuid;
    }
}
