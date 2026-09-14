package tech.skworks.tachyon.plugin.spigot.ui;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * Short class explaination
 * <p>
 * Long class explaination with @link if needed
 * </p>
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @author Jimmy (vSKAH) - 14/09/2026
 * @version 1.0
 * @since 2.0.0-alpha.2
 */
public final class SnapshotUiContext {

    private final Player viewer;
    private final OfflinePlayer target;
    private final Deque<Runnable> backStack = new ArrayDeque<>();

    public SnapshotUiContext(@NotNull final Player viewer, @NotNull final OfflinePlayer target) {
        this.viewer = Objects.requireNonNull(viewer, "viewer cannot be null");
        this.target = Objects.requireNonNull(target, "target cannot be null");
    }

    @NotNull
    public Player viewer() {
        return viewer;
    }

    @NotNull
    public OfflinePlayer target() {
        return target;
    }

    public void pushBack(@NotNull final Runnable backAction) {
        this.backStack.push(Objects.requireNonNull(backAction, "backAction cannot be null"));
    }

    public void back() {
        if (this.backStack.isEmpty()) {
            this.viewer.closeInventory();
            return;
        }
        this.backStack.pop().run();
    }

    public void clearBackStack() {
        this.backStack.clear();
    }
}
