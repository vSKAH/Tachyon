package tech.skworks.tachyon.plugin.core.component;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tech.skworks.tachyon.api.component.ComponentCodec;
import tech.skworks.tachyon.api.component.ComponentNamespace;
import tech.skworks.tachyon.api.component.ComponentPreviewHandler;
import tech.skworks.tachyon.api.component.ComponentRegistry;
import tech.skworks.tachyon.plugin.spigot.TachyonCore;
import tech.skworks.tachyon.plugin.common.util.TachyonLogger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Project Tachyon
 * Class ComponentRegistry
 *
 * @author  Jimmy (vSKAH) - 08/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class ComponentRegistryImpl implements ComponentRegistry {

    private static final TachyonLogger LOGGER = TachyonCore.getModuleLogger("ComponentRegistry");

    private final Map<ComponentNamespace, ComponentCodec<? extends Record>> codecsByNamespace = new ConcurrentHashMap<>();
    private final Map<Class<? extends Record>, ComponentCodec<? extends Record>> codecsByComponent = new ConcurrentHashMap<>();
    private final Map<Class<? extends Record>, ComponentPreviewHandler<?, ? extends Record>> previewHandlers = new ConcurrentHashMap<>();

    @Override
    public <R extends Record> void registerCodec(@NotNull ComponentCodec<R> codec) {
        Objects.requireNonNull(codec, "Codec cannot be null");

        codecsByNamespace.put(codec.getComponentNamespace(), codec);
        codecsByComponent.put(codec.getComponentClass(), codec);
        LOGGER.info("Registered component: {}:{}", codec.getComponentNamespace().toString());
    }

    @Override
    public <V, R extends Record> void registerPreviewHandler(@NotNull Class<R> recordComponentClass, @NotNull ComponentPreviewHandler<V, R> previewHandler) {
        Objects.requireNonNull(recordComponentClass, "Record class cannot be null");
        Objects.requireNonNull(previewHandler, "Preview handler cannot be null");
        previewHandlers.put(recordComponentClass, previewHandler);
        LOGGER.info("Registered component preview handler for {}", recordComponentClass.getName());
    }

    @Override
    @SuppressWarnings("unchecked")
    public @Nullable <R extends Record> ComponentCodec<R> getCodec(Class<R> recordComponentClass) {
        return (ComponentCodec<R>) codecsByComponent.get(recordComponentClass);
    }

    @Override
    @SuppressWarnings("unchecked")
    public @Nullable <R extends Record> ComponentCodec<R> getCodec(@NotNull final ComponentNamespace componentNamespace) {
        return (ComponentCodec<R>) codecsByNamespace.get(componentNamespace);
    }

    @Override
    @SuppressWarnings("unchecked")
    public @Nullable <V, R extends Record> ComponentPreviewHandler<V, R> getPreviewHandler(@NotNull Class<R> recordClass) {
        return (ComponentPreviewHandler<V, R>) previewHandlers.get(recordClass);
    }

    @Override
    public Collection<ComponentCodec<? extends Record>> getAllCodecs() {
        return Collections.unmodifiableCollection(codecsByNamespace.values());
    }

    @Override
    public int registeredCount() {
        return codecsByNamespace.size();
    }

}
