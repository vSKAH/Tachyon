package tech.skworks.tachyon.plugin.spigot.ui;

import com.tcoded.folialib.FoliaLib;
import dev.triumphteam.gui.builder.item.ItemBuilder;
import dev.triumphteam.gui.guis.BaseGui;
import dev.triumphteam.gui.guis.Gui;
import dev.triumphteam.gui.guis.GuiItem;
import dev.triumphteam.gui.guis.PaginatedGui;
import net.kyori.adventure.text.Component;
import org.apache.commons.lang3.StringUtils;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import tech.skworks.tachyon.api.component.ComponentNamespace;
import tech.skworks.tachyon.api.component.ComponentPreviewHandler;
import tech.skworks.tachyon.api.component.ComponentRegistry;
import tech.skworks.tachyon.api.snapshot.SnapshotDayInfo;
import tech.skworks.tachyon.api.snapshot.SnapshotInfo;
import tech.skworks.tachyon.api.snapshot.SnapshotService;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

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
public class SnapshotUiService {

    private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH'h' mm'm' ss's'");

    private final SnapshotService snapshotService;
    private final ComponentRegistry componentRegistry;
    private final FoliaLib foliaLib;

    public SnapshotUiService(
            @NotNull final SnapshotService snapshotService,
            @NotNull final ComponentRegistry componentRegistry,
            @NotNull final FoliaLib foliaLib
    ) {
        this.snapshotService = Objects.requireNonNull(snapshotService, "snapshotService cannot be null");
        this.componentRegistry = Objects.requireNonNull(componentRegistry, "componentRegistry cannot be null");
        this.foliaLib = Objects.requireNonNull(foliaLib, "foliaLib cannot be null");
    }

    public void openDayFolders(@NotNull final SnapshotUiContext context) {
        final var targetName = context.target().getName();
        final var targetUniqueId = context.target().getUniqueId();

        dispatchAsync(
                context.viewer(),
                this.snapshotService.getSnapshotDays(targetUniqueId.toString()),
                daysList -> {
                    final PaginatedGui gui = Gui.paginated()
                            .title(Component.text("§8History of §e" + targetName))
                            .rows(6)
                            .pageSize(45)
                            .disableAllInteractions()
                            .create();

                    for (final SnapshotDayInfo dayInfo : daysList) {
                        final LocalDate date = dayInfo.date();
                        final String dateString = date.format(DAY_FORMATTER);
                        final String displayDate = Character.toUpperCase(dateString.charAt(0)) + dateString.substring(1);

                        final GuiItem folderItem = ItemBuilder.from(Material.BOOK)
                                .name(Component.text("§6" + displayDate))
                                .lore(
                                        Component.text("§7Contains §e" + dayInfo.count() + " §7save(s)"),
                                        Component.empty(),
                                        Component.text("§a▶ Click to open this day")
                                )
                                .asGuiItem(_ -> {
                                    context.pushBack(() -> openDayFolders(context));
                                    openSnapshotDay(context, date);
                                });

                        gui.addItem(folderItem);
                    }

                    gui.setItem(6, 3, ItemBuilder.from(Material.ARROW).name(Component.text("§a◀ Previous Page")).asGuiItem(_ -> gui.previous()));
                    gui.setItem(6, 7, ItemBuilder.from(Material.ARROW).name(Component.text("§aNext Page ▶")).asGuiItem(_ -> gui.next()));

                    gui.open(context.viewer());
                },
                "Unable to fetch the snapshot list (Player: " + targetName + ")"
        );
    }

    public void openSnapshotDay(@NotNull final SnapshotUiContext context, @NotNull final LocalDate date) {
        final var targetName = context.target().getName();
        final var targetUniqueId = context.target().getUniqueId();

        dispatchAsync(
                context.viewer(),
                this.snapshotService.getSnapshotsForDay(targetUniqueId.toString(), date),
                daySnapshots -> {
                    final PaginatedGui gui = Gui.paginated()
                            .title(Component.text("§8History of §e" + targetName))
                            .rows(6)
                            .pageSize(45)
                            .disableAllInteractions()
                            .create();

                    for (final SnapshotInfo daySnapshot : daySnapshots) {
                        final String instant = Instant.ofEpochMilli(daySnapshot.timestamp())
                                .atZone(ZoneId.systemDefault())
                                .format(TIME_FORMATTER);

                        final boolean isLocked = daySnapshot.locked();
                        final Material icon = isLocked ? Material.ENCHANTED_BOOK : Material.BOOK;
                        final String lockLore = isLocked ? "§c🔒 Locked (Protected)" : "§a🔓 Unlocked (Auto-purgeable)";

                        final GuiItem folderItem = ItemBuilder.from(icon)
                                .name(Component.text("§6" + instant))
                                .lore(
                                        Component.text("§7Snapshot Id: §e" + daySnapshot.snapshotId()),
                                        Component.empty(),
                                        Component.text("§7Granularity: §e" + StringUtils.capitalize(daySnapshot.granularity().toLowerCase())),
                                        Component.text("§7Trigger Type: §e" + StringUtils.capitalize(daySnapshot.triggerType().name().toLowerCase())),
                                        Component.text("§7Source: §e" + StringUtils.capitalize(daySnapshot.source().toLowerCase())),
                                        Component.empty(),
                                        Component.text("§7Reason: §e" + StringUtils.capitalize(daySnapshot.reason())),
                                        Component.empty(),
                                        Component.text(lockLore),
                                        Component.empty(),
                                        Component.text("§a▶ Left-Click to preview data"),
                                        Component.text("§e▶ Middle-Click to toggle lock")
                                )
                                .asGuiItem(event -> {
                                    if (event.getClick() == ClickType.MIDDLE) {
                                        context.viewer().sendMessage("§7Updating the locking state...");
                                        dispatchAsync(
                                                context.viewer(),
                                                this.snapshotService.toggleSnapshotLocking(daySnapshot.snapshotId(), context.viewer().getUniqueId().toString()),
                                                locked -> {
                                                    if (locked) {
                                                        context.viewer().sendMessage("§aSuccess, the snapshot has locked!");
                                                    } else {
                                                        context.viewer().sendMessage("§eSuccess, the snapshot has unlocked !");
                                                    }
                                                    openSnapshotDay(context, date);
                                                },
                                                "Failed to update snapshot lock status"
                                        );
                                        return;
                                    }

                                    context.pushBack(() -> openSnapshotDay(context, date));
                                    openSnapshotPreview(context, daySnapshot.snapshotId(), daySnapshot.granularity());
                                });

                        gui.addItem(folderItem);
                    }

                    gui.setItem(6, 3, ItemBuilder.from(Material.ARROW).name(Component.text("§a◀ Previous Page")).asGuiItem(_ -> gui.previous()));
                    gui.setItem(6, 5, ItemBuilder.from(Material.PAPER).name(Component.text("§cReturn to Days")).asGuiItem(_ -> context.back()));
                    gui.setItem(6, 7, ItemBuilder.from(Material.ARROW).name(Component.text("§aNext Page ▶")).asGuiItem(_ -> gui.next()));

                    gui.open(context.viewer());
                },
                "Unable to fetch snapshots for this day (Player: " + targetName + ")"
        );
    }

    public void openSnapshotPreview(
            @NotNull final SnapshotUiContext context,
            @NotNull final String snapshotId,
            @NotNull final String granularity
    ) {
        context.viewer().sendMessage("§7Downloading snapshot data from backend...");

        dispatchAsync(
                context.viewer(),
                this.snapshotService.decodeSnapshot(snapshotId),
                response -> {
                    if (granularity.equalsIgnoreCase("FULL")) {
                        openFullSnapshotSubMenu(context, response);
                        return;
                    }

                    if (!response.isEmpty()) {
                        final Map.Entry<ComponentNamespace, Record> entry = response.entrySet().iterator().next();
                        openComponentDetail(context, entry.getKey(), entry.getValue());
                    } else {
                        context.viewer().sendMessage("§cThis snapshot contains no data.");
                        context.back();
                    }
                },
                "Unable to fetch the snapshot data."
        );
    }

    @SuppressWarnings("unchecked")
    public <R extends Record> void openFullSnapshotSubMenu(
            @NotNull final SnapshotUiContext context,
            @NotNull final Map<ComponentNamespace, R> componentsMap
    ) {
        final PaginatedGui gui = Gui.paginated()
                .title(Component.text("§8Select Component to Preview"))
                .rows(6)
                .pageSize(45)
                .disableAllInteractions()
                .create();

        for (final Map.Entry<ComponentNamespace, R> entry : componentsMap.entrySet()) {
            final var componentNamespace = entry.getKey();
            final var componentData = entry.getValue();

            final ComponentPreviewHandler<ItemStack, R> handler = this.componentRegistry.getPreviewHandler((Class<R>) componentData.getClass());
            final ItemStack itemStack = handler == null ? new ItemStack(Material.BARRIER) : handler.buildComponentIcon();

            final GuiItem componentIcon = ItemBuilder.from(itemStack)
                    .name(Component.text("§6" + componentNamespace))
                    .lore(Component.text("§7Click to view this component"))
                    .asGuiItem(_ -> {
                        context.pushBack(() -> openFullSnapshotSubMenu(context, componentsMap));
                        openComponentDetail(context, componentNamespace, componentData);
                    });

            gui.addItem(componentIcon);
        }

        gui.setItem(6, 3, ItemBuilder.from(Material.ARROW).name(Component.text("§a◀ Previous Page")).asGuiItem(_ -> gui.previous()));
        gui.setItem(6, 5, ItemBuilder.from(Material.PAPER).name(Component.text("§cReturn to Hours")).asGuiItem(_ -> context.back()));
        gui.setItem(6, 7, ItemBuilder.from(Material.ARROW).name(Component.text("§aNext Page ▶")).asGuiItem(_ -> gui.next()));

        gui.open(context.viewer());
    }

    @SuppressWarnings("unchecked")
    public <T extends Record> void openComponentDetail(
            @NotNull final SnapshotUiContext context,
            @NotNull final ComponentNamespace componentNamespace,
            @NotNull final T component
    ) {
        final ComponentPreviewHandler<ItemStack, T> handler = this.componentRegistry.getPreviewHandler((Class<T>) component.getClass());
        if (handler == null) {
            context.viewer().sendMessage("§cNo preview handler registered for: " + componentNamespace);
            return;
        }

        final List<ItemStack> previewItems = handler.buildComponentDataDisplay(component);
        final var guiTitle = "Preview: " + componentNamespace;

        final BaseGui baseGui;
        if (previewItems.size() > 45) {
            final PaginatedGui paginatedGui = Gui.paginated()
                    .title(Component.text(guiTitle))
                    .rows(6)
                    .pageSize(45)
                    .disableAllInteractions()
                    .create();
            paginatedGui.setItem(6, 3, ItemBuilder.from(Material.ARROW).name(Component.text("§a◀ Previous Page")).asGuiItem(_ -> paginatedGui.previous()));
            paginatedGui.setItem(6, 7, ItemBuilder.from(Material.ARROW).name(Component.text("§aNext Page ▶")).asGuiItem(_ -> paginatedGui.next()));
            baseGui = paginatedGui;
        } else {
            baseGui = Gui.gui()
                    .title(Component.text(guiTitle))
                    .rows(6)
                    .disableAllInteractions()
                    .create();
        }

        if (baseGui instanceof PaginatedGui paginatedGui) {
            for (final ItemStack item : previewItems) {
                if (item != null && item.getType() != Material.AIR) {
                    paginatedGui.addItem(new GuiItem(item));
                }
            }
        } else {
            for (ItemStack previewItem : previewItems) {
                if (previewItem != null && previewItem.getType() != Material.AIR) {
                    baseGui.addItem(new GuiItem(previewItem));
                }
            }
        }

        baseGui.setItem(6, 5, ItemBuilder.from(Material.PAPER).name(Component.text("§cGo Back")).asGuiItem(_ -> context.back()));
        baseGui.open(context.viewer());
    }

    private <T> void dispatchAsync(
            @NotNull final Player player,
            @NotNull final CompletableFuture<T> future,
            @NotNull final Consumer<T> onSuccess,
            @NotNull final String errorMessage
    ) {
        future.whenComplete((result, error) -> this.foliaLib.getScheduler().runAtEntity(player, task -> {
            if (error != null) {
                final var message = error.getMessage() != null ? error.getMessage() : errorMessage;
                player.sendMessage("§c" + message);
                return;
            }
            onSuccess.accept(result);
        }));
    }
}
