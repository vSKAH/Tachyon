package tech.skworks.tachyon.exampleplugin;

import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import tech.skworks.tachyon.api.TachyonAPI;
import tech.skworks.tachyon.exampleplugin.component.CookieComponent;

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


        var cookieCodec = new CookieComponent.CookieComponentCodec();

        tachyon.getComponentRegistry().registerCodec(cookieCodec);
        tachyon.getComponentRegistry().registerPreviewHandler(CookieComponent.class, cookieCodec);

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
