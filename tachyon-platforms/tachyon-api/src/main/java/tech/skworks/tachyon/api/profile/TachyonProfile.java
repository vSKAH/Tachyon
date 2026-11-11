package tech.skworks.tachyon.api.profile;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tech.skworks.tachyon.api.component.ComponentType;

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
    // PRIMARY HOT-PATH API: O(1) PURE, LOCK-FREE VIA COMPONENTTYPE<T>
    // =========================================================================

    /**
     * Retrieves a component using its typed ComponentType in O(1) time (~2.8ns).
     *
     * @param type The typed component type token.
     * @param <T>  The record component type.
     * @return The current component instance, or null if absent or deleted.
     */
    <T extends Record> @Nullable T getComponent(@NotNull ComponentType<T> type);

    /**
     * Retrieves a component using its typed ComponentType, returning a fallback value if absent.
     *
     * @param type         The typed component type token.
     * @param defaultValue Fallback value if absent.
     * @param <T>          The record component type.
     * @return The component or defaultValue.
     */
    <T extends Record> @NotNull T getComponent(@NotNull ComponentType<T> type, @NotNull T defaultValue);

    /**
     * Retrieves a component using its typed ComponentType, falling back to defaultSupplier if defined.
     *
     * @param type The typed component type token.
     * @param <T>  The record component type.
     * @return The component instance.
     * @throws IllegalStateException If component is absent and type has no defaultSupplier.
     */
    <T extends Record> @NotNull T getComponentOrDefault(@NotNull ComponentType<T> type);

    /**
     * Stores or overwrites a component using its typed ComponentType.
     *
     * @param type      The typed component type token.
     * @param component The new component record instance.
     * @param <T>       The record component type.
     */
    <T extends Record> void setComponent(@NotNull ComponentType<T> type, @NotNull T component);

    /**
     * Atomically computes a new component value using its typed ComponentType.
     *
     * @param type     The typed component type token.
     * @param remapper Function taking existing component (or null) and returning new non-null component.
     * @param <T>      The record component type.
     * @return The updated component record instance.
     */
    <T extends Record> T computeComponent(@NotNull ComponentType<T> type, @NotNull Function<@Nullable T, @NotNull T> remapper);

    /**
     * Atomically mutates a component using its typed ComponentType and modifier function.
     *
     * @param type     The typed component type token.
     * @param modifier Transformation function applied to current or default state.
     * @param <T>      The record component type.
     * @return The updated component record instance.
     */
    <T extends Record> @NotNull T updateComponent(@NotNull ComponentType<T> type, @NotNull UnaryOperator<T> modifier);

    /**
     * Removes a component by marking its slot as a tombstone.
     *
     * @param type The typed component type token to remove.
     */
    void removeComponent(@NotNull ComponentType<?> type);

    /**
     * Retrieves the monotonically increasing version counter for a component type in O(1).
     *
     * @param type The component type token.
     * @return The current version revision number.
     */
    long getVersion(@NotNull ComponentType<?> type);
}
