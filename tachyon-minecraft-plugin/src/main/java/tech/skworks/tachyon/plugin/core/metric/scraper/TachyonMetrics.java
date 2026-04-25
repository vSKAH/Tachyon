package tech.skworks.tachyon.plugin.core.metric.scraper;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.Histogram;
import org.jetbrains.annotations.NotNull;
import tech.skworks.tachyon.api.metrics.MetricsCollector;
import tech.skworks.tachyon.plugin.spigot.TachyonCore;
import tech.skworks.tachyon.plugin.common.util.TachyonLogger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Project Tachyon
 * Class TachyonMetrics
 *
 * @author  Jimmy (vSKAH) - 08/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class TachyonMetrics extends MetricsCollector {

    private final Path datafolder;
    private final ScheduledExecutorService scheduler;

    private static final TachyonLogger LOGGER = TachyonCore.getModuleLogger("TachyonMetrics");
    public static final Histogram GRPC_LATENCY = Histogram.build().name("tachyon_plugin_grpc_latency_seconds").help("Latence des appels gRPC vers le backend Quarkus").labelNames("server_name", "method").buckets(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0).register();
    public static final Counter GRPC_ERRORS = Counter.build().name("tachyon_plugin_grpc_errors_total").help("Erreurs rencontrées lors des appels gRPC").labelNames("server_name", "method", "error_type").register();
    public static final Gauge PROFILES_LOADED = Gauge.build().name("tachyon_plugin_profiles_cached").help("Nombre de PlayerProfiles actuellement chargés en RAM").labelNames("server_name").register();
    public static final Gauge RETRY_QUEUE_TASKS = Gauge.build().name("tachyon_plugin_retry_queue_tasks").help("Nombre total de tâches en attente de renvoi réseau").labelNames("server_name").register();
    public static final Gauge RECOVERY_FILE_SIZE = Gauge.build().name("tachyon_plugin_recovery_file_bytes").help("Taille du fichier de secours (recovery.log) en octets").labelNames("server_name").register();
    public static final Gauge RECOVERY_PENDING_FILES = Gauge.build().name("tachyon_plugin_recovery_pending_files").help("Nombre de fichiers .bin en attente dans le dossier recovery").labelNames("server_name").register();

    public TachyonMetrics(@NotNull final String serverName, @NotNull final Path datafolder) {
        super(serverName);
        this.datafolder = datafolder;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("scheduler-tachyon-metrics").factory());
    }

    @Override
    public void start() {
        this.warmMetrics();
        this.scheduler.scheduleAtFixedRate(this::updateMetrics, 4, 4, TimeUnit.SECONDS);
    }

    @Override
    public void warmMetrics() {
        PROFILES_LOADED.labels(serverName).set(0);
        RETRY_QUEUE_TASKS.labels(serverName).set(0);
        RECOVERY_FILE_SIZE.labels(serverName).set(0);
    }

    @Override
    public void updateMetrics() {
        try {
            Path recoveryDir = datafolder.resolve("recovery");

            if (!Files.exists(recoveryDir) || !Files.isDirectory(recoveryDir)) {
                RECOVERY_FILE_SIZE.labels(serverName).set(0);
                RECOVERY_PENDING_FILES.labels(serverName).set(0);
                return;
            }

            long totalSize = 0;
            int fileCount = 0;

            try (var stream = Files.newDirectoryStream(recoveryDir.resolve("datas"), "*.bin")) {
                for (Path file : stream) {
                    totalSize += Files.size(file);
                    fileCount++;
                }
            }

            RECOVERY_FILE_SIZE.labels(serverName).set(totalSize);
            RECOVERY_PENDING_FILES.labels(serverName).set(fileCount);

        } catch (Exception e) {
            LOGGER.error(e, "Unable to read the recovery directory for metrics");
        }
    }

    @Override
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.error("Metrics scheduler shutdown interrupted.");
            }
        }

        CollectorRegistry.defaultRegistry.clear();
    }

    public Histogram.Timer startGrpcTimer(String method) {
        return GRPC_LATENCY.labels(serverName, method).startTimer();
    }

    public void recordGrpcError(String method, String errorType) {
        GRPC_ERRORS.labels(serverName, method, errorType).inc();
    }

    public void updateProfilesCount(int count) {
        PROFILES_LOADED.labels(serverName).set(count);
    }

    public void updateRetryQueueSize(int totalTasks) {
        RETRY_QUEUE_TASKS.labels(serverName).set(totalTasks);
    }
}
