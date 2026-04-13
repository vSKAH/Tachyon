package tech.skworks.tachyon.plugin.internal.metric;

import io.prometheus.client.exporter.HTTPServer;
import org.bukkit.plugin.java.JavaPlugin;
import tech.skworks.tachyon.plugin.Plugin;
import tech.skworks.tachyon.plugin.internal.util.TachyonLogger;
import tech.skworks.tachyon.plugin.internal.metric.scraper.TachyonMetrics;
import tech.skworks.tachyon.plugin.internal.metric.scraper.VanillaMetrics;

/**
 * Project Tachyon
 * Class MetricManager
 *
 * @author  Jimmy (vSKAH) - 08/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class MetricsService {

    private HTTPServer httpServer;
    private final TachyonMetrics tachyonMetrics;
    private final VanillaMetrics vanillaMetrics;

    private boolean collectionRunning;

    private static final TachyonLogger LOGGER = Plugin.getModuleLogger("MetricsService");

    public MetricsService(String serverName, JavaPlugin javaPlugin) {
        this.collectionRunning = false;
        this.tachyonMetrics = new TachyonMetrics(serverName, javaPlugin);
        this.vanillaMetrics = new VanillaMetrics(serverName, javaPlugin);
    }

    public void startMetricsCollection(String host, int port) {
        try {
            httpServer = new HTTPServer(host, port);
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
