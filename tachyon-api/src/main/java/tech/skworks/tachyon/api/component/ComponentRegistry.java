package tech.skworks.tachyon.api.component;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tech.skworks.tachyon.libs.com.google.protobuf.Any;
import tech.skworks.tachyon.libs.com.google.protobuf.Message;

import java.util.Set;

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
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public interface ComponentRegistry<V> {

    /**
     * Registers a new Protobuf component into the ecosystem along with its UI preview handler.
     * <p>
     * This method extracts the descriptor from the provided default instance to map
     * both its short name and full name for future deserialization and UI lookups.
     *
     * @param defaultInstance The default, empty instance of the Protobuf message
     * (usually obtained via {@code MyComponent.getDefaultInstance()}).
     * @param previewHandler  The UI handler responsible for rendering this component in menus.
     * @param <T>             The specific type of the Protobuf message.
     */
    <T extends Message> void registerComponent(T defaultInstance, @Nullable ComponentPreviewHandler<V> previewHandler);

    /**
     * Registers a new Protobuf component without a UI preview handler.
     *
     * @param defaultInstance The default, empty instance of the Protobuf message.
     * @param <T>             The specific type of the Protobuf message.
     */
    <T extends Message> void registerComponent(T defaultInstance);

    /**
     * Unpacks a {@link Any} wrapper into its concrete Java {@link Message} instance.
     * <p>
     * The registry extracts the Type URL from the {@code Any} object, matches it against
     * registered components, and uses the correct parser to decode the inner bytes.
     *
     * @param any The Protobuf Any container received from the back-end.
     * @return The fully parsed concrete message, or {@code null} if the type is not registered.
     */
    @Nullable Message unpack(Any any);

    /**
     * Deserializes a raw byte array directly into a concrete Java {@link Message} instance,
     * using the provided full component name to determine the correct parser.
     *
     * @param componentFullName The exact full name of the Protobuf descriptor
     * (e.g., "tech.skworks.cookies.components.CookieComponent").
     * @param rawData           The raw byte array (uncompressed) to parse.
     * @return The fully parsed concrete message, or {@code null} if the name is not registered
     * or the byte sequence is invalid.
     */
    @Nullable Message unpackRawBytes(@NotNull final String componentFullName, byte[] rawData);

    /**
     * Checks if a component is registered using its full descriptor name.
     *
     * @param componentFullName The full name to check (e.g., "tech.skworks.cookies.components.CookieComponent").
     * @return {@code true} if the component is registered, {@code false} otherwise.
     */
    boolean componentFullNameRegistered(@NotNull final String componentFullName);

    /**
     * Checks if a component is registered using its short descriptor name.
     *
     * @param componentName The short name to check (e.g., "CookieComponent").
     * @return {@code true} if the component is registered, {@code false} otherwise.
     */
    boolean componentShortNameRegistered(@NotNull final String componentName);

    /**
     * Resolves the short name of a component from its full descriptor name.
     *
     * @param componentFullName The full name of the component.
     * @return The short name (e.g., "CookieComponent"), or {@code null} if the full name is unknown.
     */
    @Nullable String getComponentShortName(@NotNull final String componentFullName);

    /**
     * Retrieves an immutable set containing the full names of all currently registered components.
     *
     * @return A set of registered full descriptor names.
     */
    @NotNull Set<String> getRegisteredComponentsFullNames();

    /**
     * Retrieves an immutable set containing the short names of all currently registered components.
     *
     * @return A set of registered short descriptor names.
     */
    @NotNull Set<String> getRegisteredComponentsShortsNames();

    /**
     * Retrieves the UI preview handler associated with a specific component.
     * <p>
     * This is primarily used by the Snapshot GUI system to determine how to visually
     * display the decoded data of a target component.
     *
     * @param componentFullName The full name of the component.
     * @return The associated {@link ComponentPreviewHandler}, or {@code null} if none was provided
     * during registration or if the component is unknown.
     */
    @Nullable ComponentPreviewHandler<V> getPreviewHandler(@NotNull final String componentFullName);

    /**
     * Gets the total number of components successfully loaded into the registry.
     *
     * @return The count of registered components.
     */
    int registeredCount();

}
