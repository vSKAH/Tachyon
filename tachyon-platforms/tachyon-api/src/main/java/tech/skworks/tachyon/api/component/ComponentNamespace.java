package tech.skworks.tachyon.api.component;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable, namespace representation for Tachyon components.
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @param pluginName    The lowercase plugin name.
 * @param componentName The lowercase component name.
 * @param fullNamespace The pre-computed canonical namespace (e.g. "plugin:component").
 *
 * @author  Jimmy (vSKAH) - 23/08/2026
 * @version 1.1
 * @since 2.0.0-SNAPSHOT
 */
public record ComponentNamespace(String pluginName, String componentName, String fullNamespace) {

    private static final Pattern VALID_NAME_PATTERN = Pattern.compile("^[a-zA-Z]+$");

    /**
     * Canonical constructor with validation and pre-computed full namespace.
     */
    public ComponentNamespace {
        Objects.requireNonNull(pluginName, "pluginName cannot be null");
        Objects.requireNonNull(componentName, "componentName cannot be null");
        Objects.requireNonNull(fullNamespace, "fullNamespace cannot be null");
    }

    /**
     * Factory method with strict regex validation and lowercase normalization.
     *
     * @param pluginName    Name of the plugin (letters only).
     * @param componentName Name of the component (letters only).
     * @return A memory-optimized ComponentNameSpace instance.
     */
    public static ComponentNamespace of(String pluginName, String componentName) {
        validateName(pluginName, "pluginName");
        validateName(componentName, "componentName");

        String lowerPlugin = pluginName.toLowerCase(Locale.ROOT);
        String lowerComponent = componentName.toLowerCase(Locale.ROOT);
        String full = lowerPlugin + ":" + lowerComponent;

        return new ComponentNamespace(lowerPlugin, lowerComponent, full);
    }

    /**
     * Parses a raw namespace string in the format {@code "plugin:component"}.
     *
     * @param raw The raw formatted namespace string.
     * @return A memory-optimized ComponentNameSpace instance.
     */
    public static ComponentNamespace parse(String raw) {
        Objects.requireNonNull(raw, "raw namespace string cannot be null");
        int colonIdx = raw.indexOf(':');
        if (colonIdx <= 0 || colonIdx == raw.length() - 1) {
            throw new IllegalArgumentException("Invalid raw namespace string '" + raw + "'. Expected format 'plugin:component'.");
        }
        String plugin = raw.substring(0, colonIdx);
        String component = raw.substring(colonIdx + 1);
        return of(plugin, component);
    }

    private static void validateName(String name, String paramName) {
        Objects.requireNonNull(name, paramName + " cannot be null");
        if (!VALID_NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Invalid " + paramName + " '" + name + "'. Must contain ONLY letters (no numbers, spaces, or special characters)."
            );
        }
    }

    @Override
    public @NotNull String toString() {
        return fullNamespace;
    }
}
