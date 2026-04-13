package tech.skworks.tachyon.plugin.api;

import org.jspecify.annotations.Nullable;
import tech.skworks.tachyon.libs.protobuf.Message;
import tech.skworks.tachyon.plugin.Plugin;
import tech.skworks.tachyon.plugin.api.profile.TachyonProfile;

import java.util.UUID;

/**
 * Project Tachyon
 * Class TachyonPlugin
 *
 * @author  Jimmy (vSKAH) - 09/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public abstract class TachyonPlugin extends Plugin {
    public abstract void onTachyonPluginEnable();
    public abstract void onTachyonPluginDisable();

    @Override
    public final void onEnable() {
        Plugin core = getCore();
        if (core == null) {
            getLogger().severe("Tachyon-Core introuvable ou non démarré. ");
            getLogger().severe("Assurez-vous que 'Tachyon-Core' est dans depend: de votre plugin.yml.");
            setEnabled(false);
            return;
        }
        onTachyonPluginEnable();
    }

    @Override
    public final void onDisable() {
        onTachyonPluginDisable();
    }

    public final <T extends Message> void registerComponent(T defaultInstance) {
        getCore().getComponentRegistry().register(defaultInstance);
    }

    public final @Nullable TachyonProfile getProfile(UUID uuid) {
        return getCore().getProfileManager().get(uuid);
    }

    public final void audit(UUID uuid, String action, String details) {
        getCore().getAuditManager().log(uuid.toString(), action, details);
    }

    public Plugin getCore() {
        return (Plugin) getServer().getPluginManager().getPlugin("Tachyon-Core");
    }
}
