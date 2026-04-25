package tech.skworks.tachyon.exampleplugin;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import tech.skworks.tachyon.api.TachyonAPI;
import tech.skworks.tachyon.api.component.ComponentPreviewHandler;
import tech.skworks.tachyon.exampleplugin.components.CookieComponent;
import tech.skworks.tachyon.libs.com.google.protobuf.Message;

/**
 * Project Tachyon
 * Class TachyonCookies
 *
 * @author  Jimmy (vSKAH) - 09/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class TachyonCookies extends JavaPlugin {

    private TachyonAPI<ItemStack> tachyon;

    @Override
    public void onEnable() {
        if (!setupTachyon()) {
            getLogger().severe("TachyonApi missing ! Disabling...");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        tachyon.getComponentRegistry().registerComponent(CookieComponent.getDefaultInstance(), new ComponentPreviewHandler<>() {

            //Used inside gui /snapshot list to differentiate components inside a full snapshot
            @Override
            public ItemStack buildComponentIcon() {
                return new ItemStack(Material.COOKIE);
            }

            //Used to say how to represent the datas inside the snapshot gui
            @Override
            public <C extends Message> ItemStack[] buildComponentDataDisplay(C message) {
                CookieComponent cookieComponent = (CookieComponent) message;

                //You can use an ItemBuilder for better readability.
                ItemStack itemStack = new ItemStack(Material.COOKIE);
                ItemMeta meta = itemStack.getItemMeta();
                meta.setDisplayName(" Amount of Cookie: " + cookieComponent.getCookies());
                itemStack.setItemMeta(meta);

                return new ItemStack[]{itemStack};
            }
        });
        getCommand("cookie").setExecutor(new CookieCommand(this));
        getLogger().info("Cookie Clicker loaded !");
    }

    private boolean setupTachyon() {
        RegisteredServiceProvider<TachyonAPI> rsp = getServer().getServicesManager().getRegistration(TachyonAPI.class);
        if (rsp == null) return false;

        tachyon = rsp.getProvider();
        return tachyon != null;
    }

    public TachyonAPI<ItemStack> getTachyon() {
        return tachyon;
    }
}
