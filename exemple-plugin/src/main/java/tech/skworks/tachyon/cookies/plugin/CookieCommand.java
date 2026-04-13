package tech.skworks.tachyon.cookies.plugin;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import tech.skworks.cookies.components.CookieComponent;
import tech.skworks.tachyon.plugin.internal.player.PlayerProfile;

/**
 * Project Tachyon
 * Class CookieCommand
 *
 * @author  Jimmy (vSKAH) - 09/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class CookieCommand implements CommandExecutor {

    private final TachyonCookies plugin;

    public CookieCommand(TachyonCookies plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can execute this command.");
            return true;
        }

        PlayerProfile profile = plugin.getProfileManager().get(player.getUniqueId());
        if (profile == null) {
            player.sendMessage("§cError: Your profile is not loaded from Tachyon yet.");
            return true;
        }

        // Get the cookie component. If the profile doesn't have it, provide a default value
        CookieComponent component = profile.getComponent(CookieComponent.class, CookieComponent.newBuilder().setCookies(0).build());

        if (args.length == 1 && args[0].equalsIgnoreCase("click")) {
            long newCookiesAmount = component.getCookies() + 1;

            //Update the component
            profile.updateComponent(CookieComponent.class, (CookieComponent.Builder builder) -> {
                builder.setCookies(newCookiesAmount);
            });

            player.sendMessage("§6+1 Cookie ! §e(Total : " + newCookiesAmount + ")");
            return true;
        }

        player.sendMessage("§7Use §f/cookie click §7to gain more cookies.");
        return true;
    }
}
