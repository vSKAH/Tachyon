package tech.skworks.tachyon.api.component;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * Project Tachyon
 * Interface ComponentRegistry
 *
 * <p> The central hub for managing and resolving all Protobuf components within the Tachyon ecosystem. </p>
 * <p>
 * This registry acts as the dictionary that bridges raw Protobuf data
 * with concrete Java objects and their associated graphical user interfaces (Preview Handlers).
 * It handles the dynamic unpacking of byte arrays into typed {@link Message} instances.
 * </p>
 *
 * @param <V> The visual object type used by the {@link ComponentPreviewHandler}
 * (e.g., {@code org.bukkit.inventory.ItemStack} for Spigot).
 *
 * @author  Jimmy (vSKAH) - 18/04/2026
 * @version 2.0
 * @since 1.0.0-SNAPSHOT
 */
public interface ComponentRegistry {

    /**
     * Registers a new ComponentCodec.
     *
     * @param codec           The codec instance to register
     * @param <R>             The specific record component type.
     */
    <R extends Record> void registerComponent(@NotNull ComponentCodec<R> codec);

    /**
     * Registers a new visual preview handler for a specific component.
     *
     * @param recordComponentClass The component record class
     * @param previewHandler  The preview handler instance
     * @param <V>             The visual element type (ex ItemStack)
     * @param <R>             The record component type.
     */
    <V, R extends Record> void registerPreviewHandler(@NotNull Class<R> recordComponentClass, @NotNull ComponentPreviewHandler<V, R> previewHandler);


    /**
     * Retrieves a codec by its component Record class.
     *
     * @param recordComponentClass The Record class.
     * @param <R>                  The Record component type.
     * @return The registered codec or null if not found
     **/
    <R extends Record> @Nullable ComponentCodec<R> getCodec(Class<R> recordComponentClass);

    <R extends Record> @Nullable ComponentCodec<R> getCodec(@NotNull final ComponentNamespace componentNamespace);

    <V, R extends Record >@Nullable ComponentPreviewHandler<V, R> getPreviewHandler(@NotNull final Class<R> recordClass);

    int registeredCount();

    Collection<ComponentCodec<? extends Record>> getAllCodecs();

}
