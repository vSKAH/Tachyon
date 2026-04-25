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

    private final Set<Class<? extends Message>> dirty = ConcurrentHashMap.newKeySet();
    private final Set<String> deleted = ConcurrentHashMap.newKeySet();
    private final Map<Class<? extends Message>, Message> components = new ConcurrentHashMap<>();

    public TachyonProfileImpl(UUID uuid) {
        this.uuid = uuid;
    }

    public <T extends Message> void initComponent(@NotNull final T component) {
        components.put(component.getClass(), component);
    }

    @Override
    public <T extends Message> void setComponent(@NotNull final T component) {
        components.put(component.getClass(), component);
        dirty.add(component.getClass());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Message, B extends Message.Builder> void updateComponent(@NotNull final Class<T> clazz, @NotNull final Consumer<B> modifier) {
        T current = (T) components.get(clazz);
        if (current == null) {
            LOGGER.warn("updateComponent() called for {} on player {} but component is not loaded.", clazz.getSimpleName(), uuid);
            return;
        }
        B builder = (B) current.toBuilder();
        modifier.accept(builder);

        T updated = (T) builder.build();
        components.put(clazz, updated);
        dirty.add(clazz);
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
        dirty.remove(componentDefaultInstance.getClass());
        deleted.add(componentDefaultInstance.getDescriptorForType().getFullName());
    }

    @Override
    public boolean hasPendingChanges() {
        return !dirty.isEmpty() || !deleted.isEmpty();
    }

    @Override
    public @NotNull List<Message> extractDirtyComponents() {
        return dirty.stream().map(components::get).filter(Objects::nonNull).toList();
    }

    @Override
    public @NotNull List<String> extractDeletedComponentsUrls() {
        return List.copyOf(deleted);
    }

    @Override
    public void markAsClean(@NotNull final Collection<Class<? extends Message>> savedClasses, @NotNull final Collection<String> deletedComponent) {
        savedClasses.forEach(dirty::remove);
        deletedComponent.forEach(deleted::remove);
    }

    @Override
    public UUID getUuid() {
        return uuid;
    }
}
