package tech.skworks.tachyon.plugin.common.util;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/**
 * Project Tachyon
 * Class RecoveryLayout
 *
 * <p>Single source of truth for the on-disk dead-letter / recovery layout, shared by the writer
 * ({@code GrpcPlayerDataService}) and the metrics scraper ({@code TachyonMetrics}) so the paths
 * can never silently drift apart again.</p>
 *
 * <pre>
 * dataFolder/recovery/
 *   ├── data/         *.bin payloads waiting to be replayed (corrupt ones are renamed *.bin.corrupt)
 *   └── recovery.log  human-readable audit trail
 * </pre>
 *
 * @author  Jimmy (vSKAH)
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public final class RecoveryLayout {

    /** Glob matching the binary dead-letter payloads. */
    public static final String BIN_GLOB = "*.bin";

    private static final String ROOT = "recovery";
    private static final String DATA = "data";
    private static final String LOG_FILE = "recovery.log";

    private RecoveryLayout() {
    }

    public static @NotNull Path root(@NotNull final Path dataFolder) {
        return dataFolder.resolve(ROOT);
    }

    public static @NotNull Path dataDir(@NotNull final Path dataFolder) {
        return root(dataFolder).resolve(DATA);
    }

    public static @NotNull Path logFile(@NotNull final Path dataFolder) {
        return root(dataFolder).resolve(LOG_FILE);
    }
}
