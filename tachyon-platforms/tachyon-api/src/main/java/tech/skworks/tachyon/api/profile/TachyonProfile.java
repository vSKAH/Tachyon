package tech.skworks.tachyon.api.profile;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tech.skworks.tachyon.api.component.ComponentKey;

import java.util.UUID;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Public player data container contract for plugins and external modules.
 * <p>
 * Exposes strictly user-facing component operations. Engine lifecycle, dirty tracking,
 * and persistence internals are isolated in {@link tech.skworks.tachyon.api.profile.spi.TachyonProfileSPI}.
 * Pure interface contract (zero default code).
 * </p>
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @author Jimmy (vSKAH) - 09/04/2026
 * @version 2.0
 * @since 1.0.0-SNAPSHOT
 */
public interface TachyonProfile {

    /**
     * Gets the unique identifier of the player who owns this profile.
     *
     * @return The player's UUID.
     */
    @NotNull UUID getUuid();

    // =========================================================================
    // PRIMARY HOT-PATH API: O(1) PURE, LOCK-FREE VIA COMPONENTKEY<T>
    // =========================================================================

    /**
     * Retrieves a component using its typed ComponentKey in O(1) time (~2.8ns).
     *
     * @param key The typed component key.
     * @param <T> The record component type.
     * @return The current component instance, or null if absent or deleted.
     */
    <T extends Record> @Nullable T getComponent(@NotNull ComponentKey<T> key);

    /**
     * Retrieves a component using its typed ComponentKey, returning a fallback value if absent.
     *
     * @param key          The typed component key.
     * @param defaultValue Fallback value if absent.
     * @param <T>          The record component type.
     * @return The component or defaultValue.
     */
    <T extends Record> @NotNull T getComponent(@NotNull ComponentKey<T> key, @NotNull T defaultValue);

    /**
     * Retrieves a component using its typed ComponentKey, falling back to defaultSupplier if defined.
     *
     * @param key The typed component key.
     * @param <T> The record component type.
     * @return The component instance.
     * @throws IllegalStateException If component is absent and key has no defaultSupplier.
     */
    <T extends Record> @NotNull T getComponentOrDefault(@NotNull ComponentKey<T> key);

    /**
     * Stores or overwrites a component using its typed ComponentKey.
     *
     * @param key       The typed component key.
     * @param component The new component record instance.
     * @param <T>       The record component type.
     */
    <T extends Record> void setComponent(@NotNull ComponentKey<T> key, @NotNull T component);

    /**
     * Atomically computes a new component value using its typed ComponentKey.
     *
     * @param key      The typed component key.
     * @param remapper Function taking existing component (or null) and returning new non-null component.
     * @param <T>      The record component type.
     * @return The updated component record instance.
     */
    <T extends Record> T computeComponent(@NotNull ComponentKey<T> key, @NotNull Function<@Nullable T, @NotNull T> remapper);

    /**
     * Atomically mutates a component using its typed ComponentKey and modifier function.
     *
     * @param key      The typed component key.
     * @param modifier Transformation function applied to current or default state.
     * @param <T>      The record component type.
     * @return The updated component record instance.
     */
    <T extends Record> @NotNull T updateComponent(@NotNull ComponentKey<T> key, @NotNull UnaryOperator<T> modifier);

    /**
     * Removes a component by marking its slot as a tombstone.
     *
     * @param key The typed component key to remove.
     */
    void removeComponent(@NotNull ComponentKey<?> key);

    /**
     * Retrieves the monotonically increasing version counter for a component key in O(1).
     *
     * @param key The component key.
     * @return The current version revision number.
     */
    long getVersion(@NotNull ComponentKey<?> key);
}
