package tech.skworks.tachyon.plugin.internal.player;

import tech.skworks.tachyon.libs.protobuf.Message;
import tech.skworks.tachyon.plugin.Plugin;
import tech.skworks.tachyon.plugin.api.profile.TachyonProfile;
import tech.skworks.tachyon.plugin.internal.player.component.ComponentService;
import tech.skworks.tachyon.plugin.internal.util.TachyonLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Project Tachyon
 * Class PlayerProfile
 *
 * <p> Component lifecycle: </p>
 * <p> - initComponent()   : called at load time — sets value, does NOT mark dirty. </p>
 * <p> - setComponent()    : called by plugins — sets value AND marks dirty.</p>
 * <p> - saveComponent()   : marks dirty + immediately enqueues a gRPC save.</p>
 * <p> - saveProfile()     : flushes only dirty components to the backend.</p>
 *
 * </p> The dirty set acts as a safety net: if a saveComponent() gRPC call hasn't
 * been confirmed yet when the player disconnects, saveProfile() will catch it. </p>
 *
 * @author  Jimmy (vSKAH) - 06/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class PlayerProfile implements TachyonProfile {

    private static final TachyonLogger LOGGER = Plugin.getModuleLogger("PlayerProfile");
    private final UUID uuid;
    private final ComponentService componentService;

    private final Set<Class<?>> dirty = ConcurrentHashMap.newKeySet();
    private final Map<Class<?>, Message> components = new ConcurrentHashMap<>();

    public PlayerProfile(UUID uuid, ComponentService componentService) {
        this.uuid = uuid;
        this.componentService = componentService;
    }

    /**
     * Sets a component received from the backend at load time.
     * Does NOT mark the component as dirty — avoids unnecessary saves at disconnect.
     * Called exclusively by ProfileManager.load().
     */
    public <T extends Message> void initComponent(T component) {
        components.put(component.getClass(), component);
    }

    /**
     * Updates a component in memory and marks it dirty.
     * The component will be saved during the next saveProfile() call (e.g. at disconnect).
     * Does NOT trigger an immediate gRPC save — use saveComponent() for that.
     */
    @Override
    public <T extends Message> void setComponent(T component) {
        components.put(component.getClass(), component);
        dirty.add(component.getClass());
    }

    /**
     * Applies a mutation to an existing component and marks it dirty.
     * No-op if the component is not loaded.
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T extends Message, B extends Message.Builder> void updateComponent(Class<T> clazz, Consumer<B> modifier) {
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

    /**
     * Returns the current in-memory value of a component, or null if not loaded.
     */
    @Override
    @SuppressWarnings("unchecked")
    public @Nullable <T extends Message> T getComponent(Class<T> clazz) {
        return (T) components.get(clazz);
    }

    /**
     * Returns the current in-memory value of a component, or default value if not loaded.
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T extends Message> T getComponent(Class<T> clazz, @Nonnull T defaultValue) {
        if (!components.containsKey(clazz)) {
            setComponent(defaultValue);
            return defaultValue;
        }
        return (T) components.get(clazz);
    }

    /**
     * Saves a component immediately via gRPC AND marks it dirty as a safety net.
     * If the gRPC call hasn't completed by disconnect time, saveProfile() will retry it.
     */
    @Override
    public <T extends Message> CompletableFuture<Void> saveComponent(T component) {
        components.put(component.getClass(), component);
        dirty.add(component.getClass());
        return componentService.saveComponent(uuid, component);
    }

    /**
     * Removes a component from memory and enqueues a delete via gRPC.
     */
    @Override
    public <T extends Message> CompletableFuture<Void> deleteComponent(T defaultValue) {
        components.remove(defaultValue.getClass());
        dirty.remove(defaultValue.getClass());
        return componentService.deleteComponent(uuid, defaultValue);
    }

    /**
     * Flushes only dirty components to the backend.
     * Clears the dirty set on success. On failure, components remain dirty
     * so the next attempt (e.g. retry scheduler) will pick them up.
     */
    @Override
    public CompletableFuture<Void> saveProfile() {
        if (dirty.isEmpty()) {
            LOGGER.info("saveProfile() for {} — nothing dirty, skipping.", uuid);
            return CompletableFuture.completedFuture(null);
        }

        List<Class<?>> capturedDirtyClasses = new ArrayList<>(dirty);
        List<Message> toSave = capturedDirtyClasses.stream().map(components::get).filter(Objects::nonNull).toList();

        LOGGER.info("saveProfile() for {} — flushing {} dirty component(s): {}", uuid, toSave.size(), capturedDirtyClasses.stream().map(Class::getSimpleName).toList());

        return componentService.saveProfile(uuid, toSave).whenComplete((result, exception) -> {
            if (exception == null) {
                dirty.removeAll(capturedDirtyClasses);
                LOGGER.info("saveProfile() completed successfully for {}.", uuid);
            } else {
                LOGGER.error("saveProfile() failed for {} — {} component(s) remain dirty for retry: {}", uuid, capturedDirtyClasses.size(), exception.getMessage());
            }
        });
    }


    @Override
    public UUID getUuid() {
        return uuid;
    }
}
