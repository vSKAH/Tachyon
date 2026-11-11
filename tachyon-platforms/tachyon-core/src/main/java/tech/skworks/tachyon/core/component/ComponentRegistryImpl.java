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
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Supplier;

/**
 * The central component registry managing dense slot index allocations,
 * ComponentType tokens, serialization codecs, and visual preview handlers.
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

    // Dense slot indexation lookup structures (O(1) lock-free array indexed)
    private final Map<ComponentNamespace, ComponentType<?>> typesByNamespace = new ConcurrentHashMap<>();
    private final AtomicReferenceArray<ComponentType<?>> typesBySlot = new AtomicReferenceArray<>(MAX_SLOTS);
    private final AtomicReferenceArray<ComponentCodec<?>> codecsBySlot = new AtomicReferenceArray<>(MAX_SLOTS);
    private final AtomicInteger slotCounter = new AtomicInteger(0);

    @Override
    public <R extends Record> void registerCodec(@NotNull final ComponentCodec<R> codec) {
        Objects.requireNonNull(codec, "Codec cannot be null");

        codecsByNamespace.put(codec.getComponentNamespace(), codec);
        codecsByComponent.put(codec.getComponentClass(), codec);

        // Pre-allocate or link ComponentType and associate slot codec in O(1)
        final ComponentType<R> type = getOrCreateType(codec.getComponentNamespace(), codec.getComponentClass(), null);
        codecsBySlot.set(type.slotIndex(), codec);
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
    public <T extends Record> ComponentType<T> registerType(@NotNull final ComponentNamespace namespace,
                                                            @NotNull final Class<T> recordClass) {
        return registerType(namespace, recordClass, null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Record> ComponentType<T> registerType(@NotNull final ComponentNamespace namespace,
                                                            @NotNull final Class<T> recordClass,
                                                            @Nullable final Supplier<T> defaultSupplier) {
        Objects.requireNonNull(namespace, "namespace cannot be null");
        Objects.requireNonNull(recordClass, "recordClass cannot be null");

        return (ComponentType<T>) typesByNamespace.computeIfAbsent(namespace, ns -> {
            final int slot = slotCounter.getAndIncrement();
            if (slot >= MAX_SLOTS) {
                throw new IllegalStateException("Maximum component capacity (" + MAX_SLOTS + ") exceeded! Cannot register: " + ns);
            }
            final ComponentType<T> type = new ComponentType<>(slot, ns, recordClass, defaultSupplier);
            typesBySlot.set(slot, type);

            final ComponentCodec<?> existingCodec = codecsByNamespace.get(ns);
            if (existingCodec != null) {
                codecsBySlot.set(slot, existingCodec);
            }

            LOGGER.info("Allocated dense slot {} for component type: {}", slot, ns);
            return type;
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Record> ComponentType<T> getOrCreateType(@NotNull final ComponentNamespace namespace,
                                                               @NotNull final Class<T> recordClass,
                                                               @Nullable final Supplier<T> defaultSupplier) {
        final ComponentType<?> existing = typesByNamespace.get(namespace);
        if (existing != null) {
            return (ComponentType<T>) existing;
        }
        return registerType(namespace, recordClass, defaultSupplier);
    }

    @Override
    public @Nullable ComponentType<?> getType(@NotNull final ComponentNamespace namespace) {
        return typesByNamespace.get(namespace);
    }

    @Override
    public @Nullable ComponentType<?> getType(final int slotIndex) {
        if (slotIndex < 0 || slotIndex >= MAX_SLOTS) {
            return null;
        }
        return typesBySlot.get(slotIndex);
    }

    @Override
    public @Nullable ComponentCodec<?> getCodec(final int slotIndex) {
        if (slotIndex < 0 || slotIndex >= MAX_SLOTS) {
            return null;
        }
        return codecsBySlot.get(slotIndex);
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
