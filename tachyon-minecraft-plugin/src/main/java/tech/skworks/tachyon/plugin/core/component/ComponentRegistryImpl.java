package tech.skworks.tachyon.plugin.core.component;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tech.skworks.tachyon.api.component.ComponentPreviewHandler;
import tech.skworks.tachyon.api.component.ComponentRegistry;
import tech.skworks.tachyon.libs.com.google.protobuf.Any;
import tech.skworks.tachyon.libs.com.google.protobuf.Message;
import tech.skworks.tachyon.libs.com.google.protobuf.Parser;
import tech.skworks.tachyon.plugin.spigot.TachyonCore;
import tech.skworks.tachyon.plugin.common.util.TachyonLogger;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Project Tachyon
 * Class ComponentRegistry
 *
 * @author  Jimmy (vSKAH) - 08/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class ComponentRegistryImpl implements ComponentRegistry<ItemStack> {

    private static final TachyonLogger LOGGER = TachyonCore.getModuleLogger("ComponentRegistry");

    private final Map<String, String> componentsNames = new ConcurrentHashMap<>();
    private final Map<String, ComponentPreviewHandler<ItemStack>> componentsPreviewHandlers = new ConcurrentHashMap<>();
    private final Map<String, Parser<? extends Message>> componentsParsers = new ConcurrentHashMap<>();


    @Override
    public <T extends Message> void registerComponent(T defaultInstance, @Nullable ComponentPreviewHandler<ItemStack> previewHandler) {
        final String fullName = defaultInstance.getDescriptorForType().getFullName();
        final String shortName = defaultInstance.getDescriptorForType().getName();
        componentsParsers.put(fullName, defaultInstance.getParserForType());
        componentsNames.put(fullName, shortName);
        LOGGER.info("Registered component type: {}", fullName);
        if (previewHandler != null) {
            componentsPreviewHandlers.put(fullName, previewHandler);
            LOGGER.info("Registered component preview handler for {}", shortName);
        }
    }

    @Override
    public <T extends Message> void registerComponent(T defaultInstance) {
        registerComponent(defaultInstance, null);
    }

    @Nullable
    public Message unpack(Any any) {

        final String fullNameWithoutTypeUrl = stripTypeURL(any.getTypeUrl());
        final Parser<? extends Message> parser = componentsParsers.get(fullNameWithoutTypeUrl);

        if (parser == null) {
            LOGGER.error("No parser registered for type: {}.", fullNameWithoutTypeUrl);
            LOGGER.error("Did you call registry.register(MyMessage.getDefaultInstance()) in your plugin?");
            return null;
        }

        try {
            return parser.parseFrom(any.getValue());
        } catch (Exception e) {
            LOGGER.error(e, "Failed to parse component of type: {}. The binary value in Any.value may be corrupted or in the wrong format.", fullNameWithoutTypeUrl);
            return null;
        }
    }

    @Override
    public @Nullable Message unpackRawBytes(@NotNull String componentFullName, byte[] rawData) {
        Parser<? extends Message> parser = componentsParsers.get(componentFullName);
        if (parser == null) {
            return null;
        }
        try {
            return parser.parseFrom(rawData);
        } catch (Exception e) {
            LOGGER.error(e, "Failed to parse raw bytes for component: {}", componentFullName);
            return null;
        }
    }

    public boolean componentFullNameRegistered(@NotNull final String componentFullName) {
        return componentsNames.containsKey(componentFullName);
    }

    public boolean componentShortNameRegistered(@NotNull final String componentName) {
        return componentsNames.containsValue(componentName);
    }

    public @Nullable String getComponentShortName(@NotNull final String componentFullName) {
        return componentsNames.get(componentFullName);
    }

    public @NotNull Set<String> getRegisteredComponentsFullNames() {
        return Set.copyOf(componentsNames.keySet());
    }

    public @NotNull Set<String> getRegisteredComponentsShortsNames() {
        return Set.copyOf(componentsNames.values());
    }

    public @Nullable ComponentPreviewHandler<ItemStack> getPreviewHandler(@NotNull final String componentFullName) {
        return componentsPreviewHandlers.get(componentFullName);
    }

    public int registeredCount() {
        return componentsParsers.size();
    }

    public static String stripTypeURL(final @NotNull Message message) {
        return stripTypeURL(message.getDescriptorForType().getFullName());
    }

    public static String stripTypeURL(@NotNull final String typeURL) {
        if (!typeURL.startsWith("type.googleapis.com/")) return typeURL;
        return typeURL.replace("type.googleapis.com/", "");
    }

    public static String rebuildTypeUrl(@NotNull final String typeURL) {
        if (typeURL.startsWith("type.googleapis.com/")) return typeURL;
        return "type.googleapis.com/" + typeURL;
    }

}
