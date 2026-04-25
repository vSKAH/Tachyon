package tech.skworks.tachyon.api.metrics;

import org.jetbrains.annotations.NotNull;

/**
 * Project Tachyon
 * Class MetricsCollector
 *
 * @author  Jimmy (vSKAH) - 24/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public abstract class MetricsCollector {

    protected final String serverName;

    public MetricsCollector(@NotNull final String serverName) {
        this.serverName = serverName;
    }

    public abstract void start();
    public abstract void warmMetrics();
    public abstract void updateMetrics();
    public abstract void stop();
}
