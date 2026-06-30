package tech.skworks.tachyon.plugin.core.playerdata;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;

/**
 * Project Tachyon
 * Class PlayerDataConfig
 *
 * @author  Jimmy (vSKAH) - 24/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public record PlayerDataConfig(
        int dataLoadingMaxAttempts, int dataLoadingCycleDelay, String dataLoadingKickMsg,
        boolean enableDataAutoSave, int dataAutoSaveDelay, int dataAutoSaveMaxPerTick) {


    public static PlayerDataConfig fromFile(@NotNull final FileConfiguration fileConfiguration) {
        ConfigurationSection session = fileConfiguration.getConfigurationSection("services.player-data");

        int dataLoadingMaxAttempts = session.getInt("load.attempts.max", 5);
        int dataLoadingCycleDelay = session.getInt("load.attempts.delay", 500);

        String dataLoadingKickMsg = session.getString("load.kick_msg");

        boolean enableDataAutoSave = session.getBoolean("auto-save.enabled", true);
        int dataAutoSaveDelay = session.getInt("auto-save.delay", 10);
        int dataAutoSaveMaxPerTick = session.getInt("auto-save.max-per-tick", 20);

        return new PlayerDataConfig(dataLoadingMaxAttempts, dataLoadingCycleDelay, dataLoadingKickMsg, enableDataAutoSave, dataAutoSaveDelay, dataAutoSaveMaxPerTick);
    }


}
