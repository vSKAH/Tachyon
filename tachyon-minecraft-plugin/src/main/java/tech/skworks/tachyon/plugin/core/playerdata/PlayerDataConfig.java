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
        String recoveryLogPath, String recoveryLogName,
        String recoveryBinariesPath,
        int dataLoadingMaxAttempts, int dataLoadingCycleDelay, String dataLoadingKickMsg,
        boolean enableDataAutoSave, int dataAutoSaveDelay) {


    public static PlayerDataConfig fromFile(@NotNull final FileConfiguration fileConfiguration) {
        ConfigurationSection session = fileConfiguration.getConfigurationSection("services.player-data");

        String logPath = session.getString("recovery.logs.path", "/recovery/playerdata/");
        String recoveryLogName = session.getString("recovery.logs.name", "recovery.log");

        String binaryFolderPath = session.getString("recovery.binary_folder_path");

        int dataLoadingMaxAttempts = session.getInt("load.attempts.max", 5);
        int dataLoadingCycleDelay = session.getInt("load.attempts.delay", 500);

        String dataLoadingKickMsg = session.getString("load.kick_msg");

        boolean enableDataAutoSave = session.getBoolean("auto-save.enabled", true);
        int dataAutoSaveDelay = session.getInt("auto-save.delay", 10);

        return new PlayerDataConfig(logPath, recoveryLogName, binaryFolderPath, dataLoadingMaxAttempts, dataLoadingCycleDelay, dataLoadingKickMsg, enableDataAutoSave, dataAutoSaveDelay);
    }


}
