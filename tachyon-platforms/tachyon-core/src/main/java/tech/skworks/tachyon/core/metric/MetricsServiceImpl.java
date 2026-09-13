package tech.skworks.tachyon.core.metric;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.skworks.tachyon.api.metrics.MetricsRegistry;
import tech.skworks.tachyon.api.metrics.MetricsService;
import tech.skworks.tachyon.core.metric.filter.HttpMethodFilter;
import tech.skworks.tachyon.core.metric.filter.MetricsAuthFilter;
import tech.skworks.tachyon.core.metric.filter.RateLimitFilter;
import tech.skworks.tachyon.core.metric.scraper.TachyonCollector;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Project Tachyon
 * Class MetricsService
 *
 * @author Jimmy (vSKAH) - 08/04/2026
 * @version 2.0
 * @since 1.0.0-SNAPSHOT
 */
public class MetricsServiceImpl implements MetricsService {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tachyon/MetricsService");

    private final MetricsConfig metricsConfig;
    private final PrometheusMeterRegistry prometheusMeterRegistry;
    private final MetricsRegistryImpl metricsRegistry;
    private final TachyonCollector tachyonCollector;

    private HttpServer httpServer;
    private ExecutorService httpExecutor;


    public MetricsServiceImpl(final @NotNull String serverName, final @NotNull MetricsConfig metricsConfig) {
        Objects.requireNonNull(serverName, "serverName cannot be null");
        this.metricsConfig = Objects.requireNonNull(metricsConfig, "metricsConfig cannot be null");
        this.prometheusMeterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        this.prometheusMeterRegistry.config().commonTags("server_name", serverName);

        this.metricsRegistry = new MetricsRegistryImpl(prometheusMeterRegistry);

        this.tachyonCollector = new TachyonCollector(prometheusMeterRegistry);
        this.metricsRegistry.add(this.tachyonCollector);
    }

    @Override
    public void startMetricsCollection() {
        if (httpServer != null) {
            LOGGER.info("HTTP server already started");
            return;
        }

        if (metricsConfig.metricsPort() <= 0) {
            LOGGER.warn("Unable to start metrics on {}:{}", metricsConfig.metricsHost(), metricsConfig.metricsPort());
            return;
        }

        try {
            final var socketAddress = new InetSocketAddress(metricsConfig.metricsHost(), metricsConfig.metricsPort());
            httpServer = HttpServer.create(
                    socketAddress,
                    0,
                    "/metrics",
                    this::handleMetricsScrape,
                    new HttpMethodFilter(Set.of("GET", "HEAD")),
                    new RateLimitFilter(Duration.ofSeconds(2)),
                    new MetricsAuthFilter(metricsConfig::metricsToken)
            );
            httpExecutor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("metrics-executor-", 1).factory());
            httpServer.setExecutor(httpExecutor);
            httpServer.start();
            metricsRegistry.startAll();
            LOGGER.info("Prometheus metrics HTTP server started on {}:{}", metricsConfig.metricsHost(), metricsConfig.metricsPort());
        } catch (Exception e) {
            metricsRegistry.stopAll();
            if (httpServer != null) {
                httpServer.stop(0);
                httpServer = null;
            }
            if (httpExecutor != null) {
                httpExecutor.shutdownNow();
                httpExecutor = null;
            }
            LOGGER.error("Unable to start metrics on {}:{} (With token)", metricsConfig.metricsHost(), metricsConfig.metricsPort(), e);
        }
    }

    private void handleMetricsScrape(@NotNull final HttpExchange httpExchange) {
        try (httpExchange) {
            try {
                final var response = prometheusMeterRegistry.scrape();
                final var bytes = response.getBytes(StandardCharsets.UTF_8);

                httpExchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
                httpExchange.sendResponseHeaders(200, bytes.length);

                if ("GET".equalsIgnoreCase(httpExchange.getRequestMethod())) {
                    try (final var os = httpExchange.getResponseBody()) {
                        os.write(bytes);
                        os.flush();
                    }
                }
            } catch (IOException e) {
                final var message = e.getMessage();
                if (message != null && (message.contains("Broken pipe") || message.contains("Connection reset") || message.contains("connection abort"))) {
                    LOGGER.debug("Client disconnected while scraping /metrics: {}", message);
                } else {
                    LOGGER.error("I/O error serving /metrics HTTP request", e);
                }
            } catch (Exception e) {
                LOGGER.error("Error serving /metrics HTTP request", e);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void shutdownMetricsCollection() {
        if (httpServer == null) {
            LOGGER.info("Metrics collection is not running");
            return;
        }

        metricsRegistry.stopAll();
        LOGGER.info("All registered metric collectors have been stopped");

        httpServer.stop(0);
        httpServer = null;

        if (httpExecutor != null) {
            httpExecutor.shutdown();
            try {
                if (!httpExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    httpExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                httpExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            httpExecutor = null;
        }
    }


    @Override
    public boolean isRunning() {
        return httpServer != null;
    }

    @Override
    public @NotNull MetricsRegistry getMetricsRegistry() {
        return metricsRegistry;
    }

    public TachyonCollector getTachyonCollector() {
        return tachyonCollector;
    }
}
