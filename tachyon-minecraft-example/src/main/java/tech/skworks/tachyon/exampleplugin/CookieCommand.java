package tech.skworks.tachyon.exampleplugin;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tech.skworks.tachyon.api.TachyonAPI;
import tech.skworks.tachyon.api.audit.AuditEntry;
import tech.skworks.tachyon.api.profile.TachyonProfile;
import tech.skworks.tachyon.exampleplugin.component.CookieComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Cookie Command
 * <p>
 * Command allowing players to play Cookie Clicker and administrators to inspect, set,
 * and remove the CookieComponent to test versioning and tombstones.
 * </p>
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @author Jimmy (vSKAH) - 14/09/2026
 * @version 1.0
 * @since 2.0.0-alpha.2
 */
public class CookieCommand implements TabExecutor {

    private final TachyonAPI tachyon;

    public CookieCommand(@NotNull final TachyonCookies plugin) {
        this.tachyon = Objects.requireNonNull(plugin.getTachyon(), "tachyon cannot be null");
    }

    @Override
    public boolean onCommand(@NotNull final CommandSender sender,
                             @NotNull final Command command,
                             @NotNull final String label,
                             @NotNull final String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        final var subCommand = args[0].toLowerCase();
        return switch (subCommand) {
            case "click" -> handleClick(sender);
            case "get" -> handleGet(sender, args);
            case "set" -> handleSet(sender, args);
            case "remove" -> handleRemove(sender, args);
            default -> {
                sendHelp(sender);
                yield true;
            }
        };
    }

    private boolean handleClick(@NotNull final CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can click for cookies.");
            return true;
        }

        final UUID playerId = player.getUniqueId();
        final TachyonProfile profile = tachyon.getTachyonProfileRegistry().getProfile(playerId);
        if (profile == null) {
            player.sendMessage("§cError: Your profile is not loaded from Tachyon yet.");
            return true;
        }


        final var component = profile.updateComponent(CookieComponent.CookieComponentCodec.NAMESPACE, CookieComponent.class, () -> new CookieComponent(1), currentCookies -> new CookieComponent(currentCookies.cookiesAmount() + 1));

        this.tachyon.getAuditService().log(AuditEntry.of(playerId, "COOKIE_MODULE", "GAIN_COOKIES", "+1"));
        final Long version = profile.getVersion(CookieComponent.CookieComponentCodec.NAMESPACE);
        player.sendMessage("§6+1 Cookie! §e(Total: " + component.cookiesAmount() + " §7| Version: " + version + "§e)");
        return true;
    }

    private boolean handleGet(@NotNull final CommandSender sender, @NotNull final String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /cookie get <player>");
            return true;
        }

        final Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage("§cPlayer not found: " + args[1]);
            return true;
        }

        final TachyonProfile profile = tachyon.getTachyonProfileRegistry().getProfile(target.getUniqueId());
        if (profile == null) {
            sender.sendMessage("§cError: Profile for " + target.getName() + " is not loaded.");
            return true;
        }

        final CookieComponent component = profile.getComponent(CookieComponent.CookieComponentCodec.NAMESPACE);
        final Long version = profile.getVersion(CookieComponent.CookieComponentCodec.NAMESPACE);

        if (component == null) {
            sender.sendMessage("§e" + target.getName() + " §7has §cno CookieComponent §7(null / deleted | Version: " + version + ")");
        } else {
            sender.sendMessage("§e" + target.getName() + " §7has §6" + component.cookiesAmount() + " cookies §7(Version: " + version + ")");
        }
        return true;
    }

    private boolean handleSet(@NotNull final CommandSender sender, @NotNull final String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /cookie set <player> <amount>");
            return true;
        }

        final Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage("§cPlayer not found: " + args[1]);
            return true;
        }

        final long amount;
        try {
            amount = Long.parseLong(args[2]);
            if (amount < 0) {
                sender.sendMessage("§cAmount cannot be negative.");
                return true;
            }
        } catch (NumberFormatException e) {
            sender.sendMessage("§cInvalid amount: " + args[2]);
            return true;
        }

        final TachyonProfile profile = tachyon.getTachyonProfileRegistry().getProfile(target.getUniqueId());
        if (profile == null) {
            sender.sendMessage("§cError: Profile for " + target.getName() + " is not loaded.");
            return true;
        }

        profile.setComponent(CookieComponent.CookieComponentCodec.NAMESPACE, new CookieComponent(amount));
        this.tachyon.getAuditService().log(AuditEntry.of(target.getUniqueId(), "COOKIE_MODULE", "SET_COOKIES", String.valueOf(amount)));
        final Long version = profile.getVersion(CookieComponent.CookieComponentCodec.NAMESPACE);

        sender.sendMessage("§aSuccessfully set cookies for §e" + target.getName() + " §ato §6" + amount + " §7(Version: " + version + ")");
        return true;
    }

    private boolean handleRemove(@NotNull final CommandSender sender, @NotNull final String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /cookie remove <player>");
            return true;
        }

        final Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage("§cPlayer not found: " + args[1]);
            return true;
        }

        final TachyonProfile profile = tachyon.getTachyonProfileRegistry().getProfile(target.getUniqueId());
        if (profile == null) {
            sender.sendMessage("§cError: Profile for " + target.getName() + " is not loaded.");
            return true;
        }

        profile.removeComponent(CookieComponent.CookieComponentCodec.NAMESPACE);
        this.tachyon.getAuditService().log(AuditEntry.of(target.getUniqueId(), "COOKIE_MODULE", "REMOVE_COOKIES", "tombstone"));
        final Long version = profile.getVersion(CookieComponent.CookieComponentCodec.NAMESPACE);

        sender.sendMessage("§cRemoved CookieComponent for §e" + target.getName() + " §7(Tombstone registered | Version: " + version + ")");
        return true;
    }

    private void sendHelp(@NotNull final CommandSender sender) {
        sender.sendMessage("§6--- Cookie Commands ---");
        sender.sendMessage("§e/cookie click §7- Click to earn 1 cookie");
        sender.sendMessage("§e/cookie get <player> §7- View current cookies & component version");
        sender.sendMessage("§e/cookie set <player> <amount> §7- Set cookies & test version incrementation");
        sender.sendMessage("§e/cookie remove <player> §7- Delete component & test tombstone");
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull final CommandSender sender,
                                                @NotNull final Command command,
                                                @NotNull final String label,
                                                @NotNull final String[] args) {
        if (args.length == 1) {
            return StringUtil.copyPartialMatches(args[0], List.of("click", "get", "set", "remove"), new ArrayList<>());
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("get") || args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("remove"))) {
            final List<String> names = Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
            return StringUtil.copyPartialMatches(args[1], names, new ArrayList<>());
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
            return StringUtil.copyPartialMatches(args[2], List.of("0", "10", "100", "1000"), new ArrayList<>());
        }

        return List.of();
    }
}
