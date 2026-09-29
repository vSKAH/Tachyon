package tech.skworks.tachyon.core.playerdata;

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
        int dataLoadingMaxAttempts, int dataLoadingCycleDelay, @NotNull String dataLoadingKickMsg,
        boolean enableDataAutoSave, int dataAutoSaveDelay) {

}
