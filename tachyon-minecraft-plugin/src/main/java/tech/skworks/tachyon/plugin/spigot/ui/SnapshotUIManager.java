package tech.skworks.tachyon.plugin.spigot.ui;

import dev.triumphteam.gui.builder.item.ItemBuilder;
import dev.triumphteam.gui.guis.BaseGui;
import dev.triumphteam.gui.guis.Gui;
import dev.triumphteam.gui.guis.GuiItem;
import dev.triumphteam.gui.guis.PaginatedGui;
import net.kyori.adventure.text.Component;
import org.apache.commons.lang3.StringUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import tech.skworks.tachyon.api.component.ComponentNamespace;
import tech.skworks.tachyon.api.component.ComponentPreviewHandler;
import tech.skworks.tachyon.api.component.ComponentRegistry;
import tech.skworks.tachyon.api.snapshot.SnapshotInfo;
import tech.skworks.tachyon.api.snapshot.SnapshotService;
import tech.skworks.tachyon.plugin.spigot.TachyonCore;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Project Tachyon
 * Class SnapshotUIManager
 *
 * @author  Jimmy (vSKAH) - 16/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class SnapshotUIManager {

    private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH'h' mm'm' ss's'");

    public static void openDayFoldersGui(TachyonCore plugin, Player player, OfflinePlayer target) {
        final String targetName = target.getName();
        final UUID targetUniqueId = target.getUniqueId();
        SnapshotService snapshotService = plugin.getSnapshotService();

        snapshotService.getSnapshots(targetUniqueId.toString()).whenComplete((snapshotListResponse, error) -> {
            if (error != null) {
                player.sendMessage("§cUnable to fetch the snapshot list (Player: " + targetName + ")");
                return;
            }

            Map<LocalDate, List<SnapshotInfo>> groupedSnapshots = snapshotListResponse.stream().collect(
                    Collectors.groupingBy(
                            snap -> Instant.ofEpochMilli(snap.timestamp()).atZone(ZoneId.systemDefault()).toLocalDate(),
                            () -> new TreeMap<LocalDate, List<SnapshotInfo>>(Comparator.reverseOrder()),
                            Collectors.toList()
                    )
            );

            Bukkit.getScheduler().runTask(plugin, () -> {
                PaginatedGui gui = Gui.paginated().title(Component.text("§8History of §e" + targetName)).rows(6).pageSize(45).disableAllInteractions().create();

                for (Map.Entry<LocalDate, List<SnapshotInfo>> entry : groupedSnapshots.entrySet()) {
                    LocalDate date = entry.getKey();
                    List<SnapshotInfo> daySnapshots = entry.getValue();

                    String dateString = date.format(DAY_FORMATTER);
                    String displayDate = dateString.substring(0, 1).toUpperCase() + dateString.substring(1);

                    GuiItem folderItem = ItemBuilder.from(Material.BOOK).name(Component.text("§6" + displayDate))
                            .lore(Component.text("§7Contains §e" + daySnapshots.size() + " §7save(s)"),
                                    Component.empty(),
                                    Component.text("§a▶ Click to open this day"))
                            .asGuiItem(_ -> {
                                player.closeInventory();
                                openSnapshotFoldersGui(plugin, player, target, daySnapshots);
                            });

                    gui.addItem(folderItem);
                }

                gui.setItem(6, 3, ItemBuilder.from(Material.ARROW).name(Component.text("§a◀ Previous Page")).asGuiItem(_ -> gui.previous()));
                gui.setItem(6, 7, ItemBuilder.from(Material.ARROW).name(Component.text("§aNext Page ▶")).asGuiItem(_ -> gui.next()));

                gui.open(player);
            });
        });
    }

    private static void openSnapshotFoldersGui(TachyonCore plugin, Player player, OfflinePlayer target, List<SnapshotInfo> daySnapshots) {
        final String targetName = target.getName();
        PaginatedGui gui = Gui.paginated().title(Component.text("§8History of §e" + targetName)).rows(6).pageSize(45).disableAllInteractions().create();

        for (SnapshotInfo daySnapshot : daySnapshots) {
            String instant = Instant.ofEpochMilli(daySnapshot.timestamp()).atZone(ZoneId.systemDefault()).format(TIME_FORMATTER);

            boolean isLocked = daySnapshot.locked();
            Material icon = isLocked ? Material.ENCHANTED_BOOK : Material.BOOK;
            String lockLore = isLocked ? "§c🔒 Locked (Protected)" : "§a🔓 Unlocked (Auto-purgeable)";

            GuiItem folderItem = ItemBuilder.from(icon).name(Component.text("§6" + instant))
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
                            Component.text("§e▶ Middle-Click to toggle lock"))
                    .asGuiItem(event -> {
                        ClickType click = event.getClick();
                        if (click == ClickType.MIDDLE) {
                            player.sendMessage("§7Updating the locking state...");

                            plugin.getSnapshotService().toggleSnapshotLocking(daySnapshot.snapshotId(), player.getUniqueId().toString())
                                    .whenComplete((lockedResponse, error) -> {
                                        Bukkit.getScheduler().runTask(plugin, () -> {
                                            if (error != null) {
                                                player.sendMessage(error.getMessage());
                                                return;
                                            }

                                            if (lockedResponse) {
                                                player.sendMessage("§aSuccess, the snapshot has locked!");
                                            } else {
                                                player.sendMessage("§eSuccess, the snapshot has unlocked !");
                                            }

                                            player.closeInventory();
                                        });
                                    });
                            return;
                        }
                        player.closeInventory();
                        openSnapshotPreview(plugin, player, daySnapshot.snapshotId(), daySnapshot.granularity(), () -> openSnapshotFoldersGui(plugin, player, target, daySnapshots));
                    });

            gui.addItem(folderItem);
        }

        gui.setItem(6, 3, ItemBuilder.from(Material.ARROW).name(Component.text("§a◀ Previous Page")).asGuiItem(_ -> gui.previous()));
        gui.setItem(6, 5, ItemBuilder.from(Material.PAPER).name(Component.text("§cReturn to Days")).asGuiItem(_ -> openDayFoldersGui(plugin, player, target)));
        gui.setItem(6, 7, ItemBuilder.from(Material.ARROW).name(Component.text("§aNext Page ▶")).asGuiItem(_ -> gui.next()));

        gui.open(player);
    }

    private static void openSnapshotPreview(TachyonCore plugin, Player player, String snapshotId, String granularity, Runnable onBack) {
        player.closeInventory();
        player.sendMessage("§7Downloading snapshot data from backend...");

        plugin.getSnapshotService().decodeSnapshot(snapshotId).whenComplete((response, error) -> {

            if (error != null) {
                player.sendMessage("§cUnable to fetch the snapshot data.");
                return;
            }


            Bukkit.getScheduler().runTask(plugin, () -> {

                if (granularity.equalsIgnoreCase("FULL")) {
                    openFullSnapshotSubMenu(plugin, player, response, onBack);
                    return;
                }

                if (!response.isEmpty()) {
                   Map.Entry<ComponentNamespace, Record> entry = response.entrySet().iterator().next();
                   openComponentGui(plugin, player, entry.getKey(), entry.getValue(), onBack);
                } else {
                    player.sendMessage("§cThis snapshot contains no data.");
                    onBack.run();
                }
            });
        });
    }

    private static <R extends Record> void openFullSnapshotSubMenu(TachyonCore plugin, Player player, Map<ComponentNamespace, R> componentsMap, Runnable onBack) {
        ComponentRegistry registry = plugin.getComponentRegistry();

        PaginatedGui gui = Gui.paginated().title(Component.text("§8Select Component to Preview")).rows(6).pageSize(45).disableAllInteractions().create();

        for (Map.Entry<ComponentNamespace, R> entry : componentsMap.entrySet()) {
            final var componentNamespace = entry.getKey();
            final var componentData = entry.getValue();

            final ComponentPreviewHandler<ItemStack, R> handler = registry.getPreviewHandler((Class<R>) componentData.getClass());
            final ItemStack itemStack = handler == null ? new ItemStack(Material.BARRIER) : handler.buildComponentIcon();

            GuiItem componentIcon = ItemBuilder.from(itemStack)
                    .name(Component.text("§6" + componentNamespace))
                    .lore(Component.text("§7Click to view this component"))
                    .asGuiItem(_ -> {
                        player.closeInventory();
                        openComponentGui(plugin, player, componentNamespace, componentData, () -> openFullSnapshotSubMenu(plugin, player, componentsMap, onBack));
                    });
            gui.addItem(componentIcon);
        }

        gui.setItem(6, 3, ItemBuilder.from(Material.ARROW).name(Component.text("§a◀ Previous Page")).asGuiItem(_ -> gui.previous()));
        gui.setItem(6, 5, ItemBuilder.from(Material.PAPER).name(Component.text("§cReturn to Hours")).asGuiItem(_ -> onBack.run()));
        gui.setItem(6, 7, ItemBuilder.from(Material.ARROW).name(Component.text("§aNext Page ▶")).asGuiItem(_ -> gui.next()));

        gui.open(player);
    }

    private static <T extends Record> void openComponentGui(TachyonCore plugin, Player player, ComponentNamespace componentNamespace, T component, Runnable onBack) {
        ComponentRegistry registry = plugin.getComponentRegistry();

        if (component == null) {
            player.sendMessage("§cComponent data is null");
            return;
        }

        final ComponentPreviewHandler<ItemStack, T> handler = registry.getPreviewHandler((Class<T>) component.getClass());
        if (handler == null) {
            player.sendMessage("§cNo preview handler registered for: " + componentNamespace);
            return;
        }

        final ItemStack[] previewItems = handler.buildComponentDataDisplay(component);
        final var guiTitle = "Preview: " + componentNamespace;

        BaseGui baseGui;
        if (previewItems.length > 45) {
            baseGui = Gui.paginated().title(Component.text(guiTitle)).rows(6).pageSize(45).disableAllInteractions().create();
            baseGui.setItem(6, 3, ItemBuilder.from(Material.ARROW).name(Component.text("§a◀ Previous Page")).asGuiItem(_ -> ((PaginatedGui) baseGui).previous()));
            baseGui.setItem(6, 7, ItemBuilder.from(Material.ARROW).name(Component.text("§aNext Page ▶")).asGuiItem(_ -> ((PaginatedGui) baseGui).next()));
        } else {
            baseGui = Gui.gui().title(Component.text(guiTitle)).rows(6).disableAllInteractions().create();
        }

        if (baseGui instanceof PaginatedGui) {
            for (ItemStack item : previewItems) {
                if (item != null && item.getType() != Material.AIR) {
                    baseGui.addItem(new GuiItem(item));
                }
            }
        } else {
            for (int i = 0; i < previewItems.length; i++) {
                if (previewItems[i] != null && previewItems[i].getType() != Material.AIR) {
                    baseGui.setItem(i, new GuiItem(previewItems[i]));
                }
            }
        }

        baseGui.setItem(6, 5, ItemBuilder.from(Material.PAPER).name(Component.text("§cGo Back")).asGuiItem(_ -> onBack.run()));
        baseGui.open(player);
    }
}