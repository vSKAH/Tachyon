package tech.skworks.tachyon.plugin.core.metric;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.jetbrains.annotations.NotNull;
import tech.skworks.tachyon.plugin.spigot.TachyonCore;
import tech.skworks.tachyon.plugin.common.util.TachyonLogger;
import tech.skworks.tachyon.plugin.core.metric.scraper.TachyonMetrics;
import tech.skworks.tachyon.plugin.core.metric.scraper.VanillaMetrics;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Project Tachyon
 * Class MetricsService
 *
 * @author Jimmy (vSKAH) - 08/04/2026
 * @version 2.0
 * @since 1.0.0-SNAPSHOT
 */
public class MetricsService {

    private HttpServer httpServer;
    private final PrometheusMeterRegistry registry;
    private final TachyonMetrics tachyonMetrics;
    private final VanillaMetrics vanillaMetrics;

    private boolean collectionRunning;

    private static final TachyonLogger LOGGER = TachyonCore.getModuleLogger("MetricsService");

    public MetricsService(String serverName, TachyonCore javaPlugin) {
        this.collectionRunning = false;
        this.registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        this.tachyonMetrics = new TachyonMetrics(serverName, javaPlugin.getDataFolder().toPath(), registry);
        this.vanillaMetrics = new VanillaMetrics(serverName, javaPlugin, registry);
    }

    public void startMetricsCollection(@NotNull MetricsConfig metricsConfig) {
        if (metricsConfig.metricsPort() <= 0) {
            LOGGER.warn("Unable to start metrics on {}:{}", metricsConfig.metricsHost(), metricsConfig.metricsPort());
            return;
        }

        try {
            httpServer = HttpServer.create(new InetSocketAddress(metricsConfig.metricsHost(), metricsConfig.metricsPort()), 0);
            httpServer.createContext("/metrics", httpExchange -> {
                try {
                    if (!"GET".equalsIgnoreCase(httpExchange.getRequestMethod()) && !"HEAD".equalsIgnoreCase(httpExchange.getRequestMethod())) {
                        httpExchange.sendResponseHeaders(405, -1);
                        return;
                    }

                    String response = registry.scrape();
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    httpExchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
                    httpExchange.sendResponseHeaders(200, bytes.length);

                    if ("GET".equalsIgnoreCase(httpExchange.getRequestMethod())) {
                        try (OutputStream os = httpExchange.getResponseBody()) {
                            os.write(bytes);
                            os.flush();
                        }
                    }
                } catch (IOException e) {
                    String message = e.getMessage();
                    if (message != null && (message.contains("Broken pipe") || message.contains("Connection reset") || message.contains("connection abort"))) {
                        LOGGER.debug("Client disconnected while scraping /metrics: {}", message);
                    } else {
                        LOGGER.error(e, "I/O error serving /metrics HTTP request");
                    }
                } catch (Exception e) {
                    LOGGER.error(e, "Error serving /metrics HTTP request");
                } finally {
                    try {
                        httpExchange.close();
                    } catch (Exception ignored) {
                    }
                }
            });
            httpServer.start();

            tachyonMetrics.start();
            LOGGER.info("Metrics collection 'Tachyon' has been started");
            vanillaMetrics.start();
            LOGGER.info("Metrics collection 'Vanilla' has been started");
            collectionRunning = true;
        } catch (Exception e) {
            if (httpServer != null) {
                httpServer.stop(0);
                httpServer = null;
            }
            LOGGER.error(e, "Unable to start metrics on {}:{}", metricsConfig.metricsHost(), metricsConfig.metricsPort());
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

        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        collectionRunning = false;
    }

    public boolean metricsCollectionRunning() {
        return httpServer != null && collectionRunning;
    }

    public TachyonMetrics getTachyonMetrics() {
        return tachyonMetrics;
    }
}
