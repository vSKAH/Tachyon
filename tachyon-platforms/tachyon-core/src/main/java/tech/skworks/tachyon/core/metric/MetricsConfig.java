package tech.skworks.tachyon.core.metric;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Project Tachyon
 * Class MetricsConfiguration
 *
 * @author  Jimmy (vSKAH) - 24/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public record MetricsConfig(@NotNull String metricsHost, @NotNull Integer metricsPort, @Nullable String metricsToken) {
}
