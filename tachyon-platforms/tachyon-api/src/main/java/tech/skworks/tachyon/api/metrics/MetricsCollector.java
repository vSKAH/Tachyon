package tech.skworks.tachyon.api.metrics;

/**
 * Project Tachyon
 * Class MetricsCollector
 *
 * @author  Jimmy (vSKAH) - 24/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public abstract class MetricsCollector {
    public abstract void start();
    public void updateMetrics() {};
    public abstract void stop();
}
