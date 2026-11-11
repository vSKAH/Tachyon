package tech.skworks.tachyon.core.component;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.skworks.tachyon.api.component.*;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * The central component registry managing dense slot index allocations,
 * ComponentKey tokens, serialization codecs, and visual preview handlers.
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @author Jimmy (vSKAH) - 08/04/2026
 * @version 2.0
 * @since 1.0.0-SNAPSHOT
 */
public class ComponentRegistryImpl implements ComponentRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tachyon/ComponentRegistry");
    public static final int MAX_SLOTS = 128;

    private final Map<ComponentNamespace, ComponentCodec<? extends Record>> codecsByNamespace = new ConcurrentHashMap<>();
    private final Map<Class<? extends Record>, ComponentCodec<? extends Record>> codecsByComponent = new ConcurrentHashMap<>();
    private final Map<Class<? extends Record>, ComponentPreviewHandler<?, ? extends Record>> previewHandlers = new ConcurrentHashMap<>();

    // Dense slot indexation lookup structures
    private final Map<ComponentNamespace, ComponentKey<?>> keysByNamespace = new ConcurrentHashMap<>();
    private final Map<Integer, ComponentKey<?>> keysBySlot = new ConcurrentHashMap<>();
    private final AtomicInteger slotCounter = new AtomicInteger(0);

    @Override
    public <R extends Record> void registerCodec(@NotNull final ComponentCodec<R> codec) {
        Objects.requireNonNull(codec, "Codec cannot be null");

        codecsByNamespace.put(codec.getComponentNamespace(), codec);
        codecsByComponent.put(codec.getComponentClass(), codec);

        // Pre-allocate or link ComponentKey
        getOrCreateKey(codec.getComponentNamespace(), codec.getComponentClass(), null);
        LOGGER.info("Registered component codec: {}", codec.getComponentNamespace());
    }

    @Override
    public <V, R extends Record> void registerPreviewHandler(@NotNull final Class<R> recordComponentClass,
                                                             @NotNull final ComponentPreviewHandler<V, R> previewHandler) {
        Objects.requireNonNull(recordComponentClass, "Record class cannot be null");
        Objects.requireNonNull(previewHandler, "Preview handler cannot be null");
        previewHandlers.put(recordComponentClass, previewHandler);
        LOGGER.info("Registered component preview handler for {}", recordComponentClass.getName());
    }

    @Override
    public <T extends Record> ComponentKey<T> registerKey(@NotNull final ComponentNamespace namespace,
                                                          @NotNull final Class<T> type) {
        return registerKey(namespace, type, null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Record> ComponentKey<T> registerKey(@NotNull final ComponentNamespace namespace,
                                                          @NotNull final Class<T> type,
                                                          @Nullable final Supplier<T> defaultSupplier) {
        Objects.requireNonNull(namespace, "namespace cannot be null");
        Objects.requireNonNull(type, "type cannot be null");

        return (ComponentKey<T>) keysByNamespace.computeIfAbsent(namespace, ns -> {
            final int slot = slotCounter.getAndIncrement();
            if (slot >= MAX_SLOTS) {
                throw new IllegalStateException("Maximum component capacity (" + MAX_SLOTS + ") exceeded! Cannot register: " + ns);
            }
            final ComponentKey<T> key = new ComponentKey<>(slot, ns, type, defaultSupplier);
            keysBySlot.put(slot, key);
            LOGGER.info("Allocated dense slot {} for component key: {}", slot, ns);
            return key;
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Record> ComponentKey<T> getOrCreateKey(@NotNull final ComponentNamespace namespace,
                                                             @NotNull final Class<T> type,
                                                             @Nullable final Supplier<T> defaultSupplier) {
        final ComponentKey<?> existing = keysByNamespace.get(namespace);
        if (existing != null) {
            return (ComponentKey<T>) existing;
        }
        return registerKey(namespace, type, defaultSupplier);
    }

    @Override
    public @Nullable ComponentKey<?> getKey(@NotNull final ComponentNamespace namespace) {
        return keysByNamespace.get(namespace);
    }

    @Override
    public @Nullable ComponentKey<?> getKey(final int slotIndex) {
        return keysBySlot.get(slotIndex);
    }

    @Override
    @SuppressWarnings("unchecked")
    public @Nullable <R extends Record> ComponentCodec<R> getCodec(final Class<R> recordComponentClass) {
        return (ComponentCodec<R>) codecsByComponent.get(recordComponentClass);
    }

    @Override
    @SuppressWarnings("unchecked")
    public @Nullable <R extends Record> ComponentCodec<R> getCodec(@NotNull final ComponentNamespace componentNamespace) {
        return (ComponentCodec<R>) codecsByNamespace.get(componentNamespace);
    }

    @Override
    @SuppressWarnings("unchecked")
    public @Nullable <V, R extends Record> ComponentPreviewHandler<V, R> getPreviewHandler(@NotNull final Class<R> recordClass) {
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
