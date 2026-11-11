package tech.skworks.tachyon.api.component;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.function.Supplier;

/**
 * The central hub for managing and resolving all Protobuf and Record components within Tachyon.
 * <p>
 * Manages dense slot allocations, typed {@link ComponentType} tokens, codecs, and GUI previews.
 * Pure interface contract (no default implementation code).
 * </p>
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @author Jimmy (vSKAH) - 18/04/2026
 * @version 2.0
 * @since 1.0.0-SNAPSHOT
 */
public interface ComponentRegistry {

    /**
     * Maximum number of concurrent component types supported by the dense slot indexing array.
     */
    int MAX_SUPPORTED_COMPONENTS = 128;

    /**
     * Registers a new typed component type with an optional default supplier.
     *
     * @param namespace       The canonical namespace of the component.
     * @param recordClass     The component record class.
     * @param defaultSupplier Optional default supplier.
     * @param <T>             The component record type.
     * @return The registered ComponentType with an assigned dense slotIndex.
     */
    <T extends Record> ComponentType<T> registerType(@NotNull ComponentNamespace namespace, @NotNull Class<T> recordClass, @Nullable Supplier<T> defaultSupplier);

    /**
     * Registers a new typed component type without a default supplier.
     *
     * @param namespace   The canonical namespace of the component.
     * @param recordClass The component record class.
     * @param <T>         The component record type.
     * @return The registered ComponentType with an assigned dense slotIndex.
     */
    <T extends Record> ComponentType<T> registerType(@NotNull ComponentNamespace namespace, @NotNull Class<T> recordClass);

    /**
     * Retrieves an existing ComponentType by its namespace, or null if not registered.
     *
     * @param namespace The canonical namespace.
     * @return The ComponentType or null.
     */
    @Nullable ComponentType<?> getType(@NotNull ComponentNamespace namespace);

    /**
     * Retrieves an existing ComponentType by its allocated dense slotIndex, or null.
     *
     * @param slotIndex The dense slot index (0 <= slotIndex < 128).
     * @return The ComponentType or null.
     */
    @Nullable ComponentType<?> getType(int slotIndex);

    /**
     * Retrieves an existing ComponentType or registers a new one atomically.
     *
     * @param namespace       The canonical namespace of the component.
     * @param recordClass     The component record class.
     * @param defaultSupplier Optional default supplier.
     * @param <T>             The component record type.
     * @return The existing or newly registered ComponentType.
     */
    <T extends Record> ComponentType<T> getOrCreateType(@NotNull ComponentNamespace namespace, @NotNull Class<T> recordClass, @Nullable Supplier<T> defaultSupplier);

    /**
     * Registers a new ComponentCodec.
     *
     * @param codec The codec instance to register.
     * @param <R>   The specific record component type.
     */
    <R extends Record> void registerCodec(@NotNull ComponentCodec<R> codec);

    /**
     * Registers a new visual preview handler for a specific component.
     *
     * @param recordComponentClass The component record class.
     * @param previewHandler       The preview handler instance.
     * @param <V>                  The visual element type (e.g. ItemStack).
     * @param <R>                  The record component type.
     */
    <V, R extends Record> void registerPreviewHandler(@NotNull Class<R> recordComponentClass, @NotNull ComponentPreviewHandler<V, R> previewHandler);

    /**
     * Retrieves a codec by its component Record class.
     *
     * @param recordComponentClass The Record class.
     * @param <R>                  The Record component type.
     * @return The registered codec or null if not found.
     */
    <R extends Record> @Nullable ComponentCodec<R> getCodec(Class<R> recordComponentClass);

    /**
     * Retrieves a codec by its component namespace.
     *
     * @param componentNamespace The component namespace.
     * @param <R>                The Record component type.
     * @return The registered codec or null if not found.
     */
    <R extends Record> @Nullable ComponentCodec<R> getCodec(@NotNull ComponentNamespace componentNamespace);

    /**
     * Retrieves a preview handler for a specific component class.
     *
     * @param recordClass The Record class.
     * @param <V>         The visual element type.
     * @param <R>         The Record component type.
     * @return The preview handler or null if not found.
     */
    <V, R extends Record> @Nullable ComponentPreviewHandler<V, R> getPreviewHandler(@NotNull Class<R> recordClass);

    /**
     * Returns the total number of registered codecs.
     *
     * @return Number of registered codecs.
     */
    int registeredCount();

    /**
     * Returns an unmodifiable view of all registered codecs.
     *
     * @return All registered codecs.
     */
    Collection<ComponentCodec<? extends Record>> getAllCodecs();
}
