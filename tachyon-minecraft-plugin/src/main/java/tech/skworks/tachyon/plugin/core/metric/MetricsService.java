package tech.skworks.tachyon.plugin.core.metric;

import io.prometheus.client.exporter.HTTPServer;
import org.jetbrains.annotations.NotNull;
import tech.skworks.tachyon.plugin.spigot.TachyonCore;
import tech.skworks.tachyon.plugin.common.util.TachyonLogger;
import tech.skworks.tachyon.plugin.core.metric.scraper.TachyonMetrics;
import tech.skworks.tachyon.plugin.core.metric.scraper.VanillaMetrics;

/**
 * Project Tachyon
 * Class MetricManager
 *
 * @author  Jimmy (vSKAH) - 08/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class MetricsService {

    //TODO: dynamicly injects metrics collectors
    private HTTPServer httpServer;
    private final TachyonMetrics tachyonMetrics;
    private final VanillaMetrics vanillaMetrics;

    private boolean collectionRunning;

    private static final TachyonLogger LOGGER = TachyonCore.getModuleLogger("MetricsService");

    public MetricsService(String serverName, TachyonCore javaPlugin) {
        this.collectionRunning = false;
        this.tachyonMetrics = new TachyonMetrics(serverName, javaPlugin.getDataFolder().toPath());
        this.vanillaMetrics = new VanillaMetrics(serverName, javaPlugin);
    }

    public void startMetricsCollection(@NotNull MetricsConfig metricsConfig) {
        try {
            httpServer = new HTTPServer(metricsConfig.metricsHost(), metricsConfig.metricsPort());
            tachyonMetrics.start();
            LOGGER.info("Metrics collection 'Tachyon' has been started");
            vanillaMetrics.start();
            LOGGER.info("Metrics collection 'Vanilla' has been started");
            collectionRunning = true;
        } catch (Exception e) {
            httpServer.close();
            httpServer = null;
            LOGGER.error(e, "Unable to start metrics.");
        }
    }

    public void shutdownMetricsCollection() {
        if (!metricsCollectionRunning()) {
            LOGGER.info("Metrics collection is not running");
            return;
        }
        tachyonMetrics.stop();
        LOGGER.info("Metrics collection 'Tachyon' has been stopped");

        vanillaMetrics.stop();
        LOGGER.info("Metrics collection 'Vanilla' has been stopped");
        httpServer.close();
        httpServer = null;
    }

    public boolean metricsCollectionRunning() {
        return httpServer != null && collectionRunning;
    }

    public VanillaMetrics getVanillaMetrics() {
        return vanillaMetrics;
    }

    public TachyonMetrics getTachyonMetrics() {
        return tachyonMetrics;
    }
}
