package tech.skworks.tachyon.plugin.internal.player.component;

import tech.skworks.tachyon.libs.protobuf.Any;
import tech.skworks.tachyon.libs.protobuf.Message;
import tech.skworks.tachyon.libs.protobuf.Parser;
import tech.skworks.tachyon.plugin.Plugin;
import tech.skworks.tachyon.plugin.internal.util.TachyonLogger;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Project Tachyon
 * Class ComponentRegistry
 *
 * @author  Jimmy (vSKAH) - 08/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class ComponentRegistry {

    private static final TachyonLogger LOGGER = Plugin.getModuleLogger("ComponentRegistry");
    private final Map<String, Parser<? extends Message>> parsers = new ConcurrentHashMap<>();


    public <T extends Message> void register(T defaultInstance) {
        String fullName = defaultInstance.getDescriptorForType().getFullName();
        parsers.put(fullName, defaultInstance.getParserForType());
        LOGGER.info("Registered component type: {}", fullName);
    }

    @Nullable
    public Message unpack(Any any) {
        String fullName = any.getTypeUrl().replace("type.googleapis.com/", "");
        Parser<? extends Message> parser = parsers.get(fullName);

        if (parser == null) {
            LOGGER.error("No parser registered for type: {}.", fullName);
            LOGGER.error("Did you call registry.register(MyMessage.getDefaultInstance()) in your plugin?");
            return null;
        }

        try {
            return parser.parseFrom(any.getValue());
        } catch (Exception e) {
            LOGGER.error(e, "Failed to parse component of type: {}. The binary value in Any.value may be corrupted or in the wrong format.", fullName);
            return null;
        }
    }

    public boolean isRegistered(String protoFullName) {
        return parsers.containsKey(protoFullName);
    }
    public int registeredCount() {
        return parsers.size();
    }
}
