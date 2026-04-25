package tech.skworks.tachyon.plugin.spigot.command;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tech.skworks.tachyon.api.profile.TachyonProfile;
import tech.skworks.tachyon.api.services.SnapshotService;
import tech.skworks.tachyon.service.contracts.snapshot.SnapshotTriggerType;
import tech.skworks.tachyon.libs.com.google.protobuf.Message;
import tech.skworks.tachyon.plugin.spigot.TachyonCore;
import tech.skworks.tachyon.plugin.spigot.ui.SnapshotUIManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Project Tachyon
 * Class SnapshotCommand
 *
 * @author  Jimmy (vSKAH) - 17/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class SnapshotCommand implements TabExecutor {

    private final TachyonCore tachyonCore;
    private final SnapshotService snapshotService;

    public SnapshotCommand(TachyonCore tachyonCore) {
        this.tachyonCore = tachyonCore;
        this.snapshotService = tachyonCore.getSnapshotService();
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6--- Snapshot Command Help ---");
        sender.sendMessage("§e/snapshot list <target> §7- Opens snapshot history");
        sender.sendMessage("§e/snapshot take full <target> [reason] §7- Takes full backup");
        sender.sendMessage("§e/snapshot take component <target> <component> [reason] - Takes backup of specific component");
    }

    private String buildReason(@NotNull String[] args, int startIndex) {
        if (args.length <= startIndex) {
            return "Manual command trigger";
        }
        return String.join(" ", Arrays.copyOfRange(args, startIndex, args.length));
    }

    @Nullable
    private <T extends Message> T fetchComponentByName(@NotNull UUID uuid, @NotNull String componentName) {
        final TachyonProfile profile = tachyonCore.getTachyonProfileRegistry().getProfile(uuid);
        if (profile == null) return null;
        return profile.getComponent(componentName);
    }

    private @Nullable OfflinePlayer getOfflinePlayer(@NotNull final String targetName) {
        OfflinePlayer target = Bukkit.getPlayer(targetName);

        if (target == null) {
            target = Bukkit.getOfflinePlayer(targetName);
            if (target.getFirstPlayed() == 0) {
                return null;
            }
        }
        return target;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("tachyon.command.snapshot")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }

        if (args == null || args.length < 2) {
            sendHelp(sender);
            return true;
        }

        final String action = args[0].toLowerCase();

        if (action.equals("list")) {
            final String targetName = args[1];
            final OfflinePlayer target = getOfflinePlayer(targetName);
            if (target == null) {
                sender.sendMessage("§cPlayer not found.");
                return true;
            }
            openSnapshotList(sender, target);
            return true;
        }

        if (action.equals("take")) {
            if (args.length < 3) {
                sendHelp(sender);
                return true;
            }

            final String type = args[1].toLowerCase();
            final String targetName = args[2];
            final OfflinePlayer target = getOfflinePlayer(targetName);

            if (target == null) {
                sender.sendMessage("§cPlayer not found.");
                return true;
            }

            final String targetUniqueId = target.getUniqueId().toString();

            if (type.equals("full")) {
                final String reason = buildReason(args, 3);
                takeFullSnapshot(sender, targetName, targetUniqueId, reason);
                return true;
            }

            if (type.equals("component")) {
                if (args.length < 4) {
                    sender.sendMessage("§cUsage: /snapshot take component <player> <component> [reason]");
                    return true;
                }

                final String componentName = args[3];
                final String reason = buildReason(args, 4);

                takeComponentSnapshot(sender, targetName, target.getUniqueId(), reason, componentName);
                return true;
            }
        }

        sendHelp(sender);
        return true;
    }

    private void openSnapshotList(CommandSender sender, OfflinePlayer target) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can open the snapshot list");
            return;
        }
        sender.sendMessage("§eOpening snapshot history for " + target.getName() + "...");
        SnapshotUIManager.openDayFoldersGui(tachyonCore, player, target);
    }

    private void takeFullSnapshot(CommandSender sender, String targetName, String targetUniqueId, String reason) {
        sender.sendMessage("§7Initiating full snapshot for " + targetName + "...");

        snapshotService.takeDatabaseSnapshot(targetUniqueId, reason, SnapshotTriggerType.SNAPSHOT_TRIGGER_MANUAL)
                .whenComplete((v, ex) -> {
                    if (ex != null) {
                        sender.sendMessage("§cFailed to take full snapshot for " + targetName + ".");
                        return;
                    }
                    sender.sendMessage("§aFull snapshot successfully taken for " + targetName + ".");
                });
    }

    private <T extends Message> void takeComponentSnapshot(CommandSender sender, String targetName, UUID targetUniqueId, String reason, String componentName) {
        T component = fetchComponentByName(targetUniqueId, componentName);

        if (component == null) {
            sender.sendMessage("§cUnknown component or data unavailable: " + componentName);
            return;
        }

        System.out.println("Component: " + component.toString());


        sender.sendMessage("§7Initiating component snapshot (" + componentName + ") for " + targetName + "...");

        snapshotService.takeComponentSnapshot(targetUniqueId.toString(), reason, SnapshotTriggerType.SNAPSHOT_TRIGGER_MANUAL, component).whenComplete((v, ex) -> {
            if (ex != null) {
                sender.sendMessage("§cFailed to take specific snapshot for " + targetName + ".");
                return;
            }
            sender.sendMessage("§aComponent snapshot successfully taken for " + targetName + ".");
        });
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();

        if (args.length == 1) {
            suggestions.addAll(List.of("list", "take"));
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("list")) {
                return null;
            } else if (args[0].equalsIgnoreCase("take")) {
                suggestions.addAll(List.of("full", "component"));
            }
        } else if (args.length == 3) {
            if (args[0].equalsIgnoreCase("take")) {
                return null;
            }
        } else if (args.length == 4) {
            if (args[0].equalsIgnoreCase("take") && args[1].equalsIgnoreCase("component")) {
                suggestions.addAll(tachyonCore.getComponentRegistry().getRegisteredComponentsShortsNames());
            }
        }

        StringUtil.copyPartialMatches(args[args.length - 1], suggestions, completions);
        return completions;
    }
}
