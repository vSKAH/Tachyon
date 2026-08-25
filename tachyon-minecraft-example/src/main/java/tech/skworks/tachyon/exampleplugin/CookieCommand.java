package tech.skworks.tachyon.exampleplugin;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import tech.skworks.tachyon.api.TachyonAPI;
import tech.skworks.tachyon.api.audit.AuditEntry;
import tech.skworks.tachyon.api.profile.TachyonProfile;
import tech.skworks.tachyon.exampleplugin.component.CookieComponent;

import java.util.UUID;

/**
 * Project Tachyon
 * Class CookieCommand
 *
 * @author  Jimmy (vSKAH) - 09/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class CookieCommand implements CommandExecutor {

    private final TachyonAPI tachyon;

    public CookieCommand(TachyonCookies plugin) {
        this.tachyon = plugin.getTachyon();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can execute this command.");
            return true;
        }

        final UUID playerId = player.getUniqueId();
        final TachyonProfile profile = tachyon.getTachyonProfileRegistry().getProfile(playerId);
        if (profile == null) {
            player.sendMessage("§cError: Your profile is not loaded from Tachyon yet.");
            return true;
        }

        // Get the cookie component. If the profile doesn't have it, provide a default value
        CookieComponent component = profile.getComponent(CookieComponent.class, new CookieComponent(0));

        if (args.length == 1 && args[0].equalsIgnoreCase("click")) {
            long newCookiesAmount = component.cookiesAmount() + 1;

            //Update the component
            profile.updateComponent(CookieComponent.class, (value) -> value.toBuilder().cookiesAmount(newCookiesAmount).build());

            tachyon.getAuditService().log(AuditEntry.of(playerId, "COOKIE_MODULE", "GAIN_COOKIES", "+1"));
            player.sendMessage("§6+1 Cookie ! §e(Total : " + newCookiesAmount + ")");
            return true;
        }

        player.sendMessage("§7Use §f/cookie click §7to gain more cookies.");
        return true;
    }
}
