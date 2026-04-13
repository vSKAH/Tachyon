package tech.skworks.tachyon.cookies.plugin;

import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import tech.skworks.cookies.components.CookieComponent;
import tech.skworks.tachyon.api.TachyonAPI;

/**
 * Project Tachyon
 * Class TachyonCookies
 *
 * @author  Jimmy (vSKAH) - 09/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class TachyonCookies extends JavaPlugin {

    private TachyonAPI tachyon;

    @Override
    public void onEnable() {
        if (!setupTachyon()) {
            getLogger().severe("TachyonApi missing ! Disabling...");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        tachyon.registerComponent(CookieComponent.getDefaultInstance());
        getCommand("cookie").setExecutor(new CookieCommand(this));
        getLogger().info("Cookie Clicker loaded !");
    }

    private boolean setupTachyon() {
        RegisteredServiceProvider<TachyonAPI> rsp = getServer().getServicesManager().getRegistration(TachyonAPI.class);
        if (rsp == null) return false;

        tachyon = rsp.getProvider();
        return tachyon != null;
    }

    public TachyonAPI getTachyon() {
        return tachyon;
    }
}
