package tech.skworks.tachyon.api.handler;

import tech.skworks.tachyon.libs.com.google.protobuf.Message;

/**
 * Project Tachyon
 * Class ComponentPreviewHandler
 *
 * Interface defining the preview handler for a specific component type.
 * <p>
 * This handler bridges the gap between the raw data stored in the database and the user interface.
 * It is responsible for translating Protobuf messages into understandable visual elements
 * that can be displayed in menus.
 *
 * @param <T> The visual object type used by the client's graphical interface
 * (e.g., {@code org.bukkit.inventory.ItemStack} for a Spigot/Paper plugin)
 *
 * @author  Jimmy (vSKAH) - 17/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public interface ComponentPreviewHandler<T> {

    /**
     * Builds the global representative icon for this component.
     * <p>
     * This icon is typically used in selection and navigation menus (for example,
     * in the sub-menu of a FULL snapshot to list all components available for viewing).
     *
     * @return The visual object (of type {@link T}) serving as the icon to represent this component.
     */
    T buildComponentIcon();

    /**
     * Builds the detailed data display of the component from its Protobuf message.
     * <p>
     * This method takes the decoded business data and transforms it into a structured set
     * of visual elements. The returned array will be directly injected into the final
     * inventory or preview menu (e.g., the exact layout of items in an Enderchest,
     * or a paginated list of statistics).
     *
     * @param message The Protobuf message instance containing the raw player data for this component.
     * @param <C>     The specific type of the Protobuf message (must implement {@link Message}).
     * @return An array of visual objects (of type {@link T}) representing the complete and formatted content of the component.
     */
    <C extends Message> T[] buildComponentDataDisplay(C message);

}
