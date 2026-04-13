package tech.skworks.tachyon.cookies.plugin;

import tech.skworks.cookies.components.CookieComponent;
import tech.skworks.tachyon.plugin.api.TachyonPlugin;

/**
 * Project Tachyon
 * Class TachyonCookies
 *
 * @author  Jimmy (vSKAH) - 09/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class TachyonCookies extends TachyonPlugin {

    @Override
    public void onTachyonPluginEnable() {
        registerComponent(CookieComponent.getDefaultInstance());
        if (getCommand("cookie") != null) {
            getCommand("cookie").setExecutor(new CookieCommand(this));
            getLogger().info("Module Cookie Clicker chargé avec succès !");
        } else {
            getLogger().severe("Impossible d'enregistrer /cookie (oublié dans le plugin.yml ?)");
        }
    }

    @Override
    public void onTachyonPluginDisable() {

    }
}
