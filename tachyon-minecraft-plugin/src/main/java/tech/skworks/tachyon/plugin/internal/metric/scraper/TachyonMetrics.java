package tech.skworks.tachyon.plugin.internal.metric.scraper;

import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.Histogram;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import tech.skworks.tachyon.plugin.TachyonCore;
import tech.skworks.tachyon.plugin.internal.util.TachyonLogger;

import java.io.File;

/**
 * Project Tachyon
 * Class TachyonMetrics
 *
 * @author  Jimmy (vSKAH) - 08/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class TachyonMetrics {

    private final String serverName;
    private final org.bukkit.plugin.Plugin plugin;
    private BukkitTask metricsTask;

    private static final TachyonLogger LOGGER = TachyonCore.getModuleLogger("TachyonMetrics");
    public static final Histogram GRPC_LATENCY = Histogram.build().name("tachyon_plugin_grpc_latency_seconds").help("Latence des appels gRPC vers le backend Quarkus").labelNames("server_name", "method").buckets(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0).register();
    public static final Counter GRPC_ERRORS = Counter.build().name("tachyon_plugin_grpc_errors_total").help("Erreurs rencontrées lors des appels gRPC").labelNames("server_name", "method", "error_type").register();
    public static final Gauge PROFILES_LOADED = Gauge.build().name("tachyon_plugin_profiles_cached").help("Nombre de PlayerProfiles actuellement chargés en RAM").labelNames("server_name").register();
    public static final Gauge RETRY_QUEUE_TASKS = Gauge.build().name("tachyon_plugin_retry_queue_tasks").help("Nombre total de tâches en attente de renvoi réseau").labelNames("server_name").register();
    public static final Gauge RECOVERY_FILE_SIZE = Gauge.build().name("tachyon_plugin_recovery_file_bytes").help("Taille du fichier de secours (recovery.log) en octets").labelNames("server_name").register();

    public TachyonMetrics(String serverName, JavaPlugin plugin) {
        this.serverName = serverName;
        this.plugin = plugin;
    }

    public void start() {
        warmStat();
        this.metricsTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::updateStaticMetrics, 200L, 200L);
    }

    private void warmStat() {
        PROFILES_LOADED.labels(serverName).set(0);
        RETRY_QUEUE_TASKS.labels(serverName).set(0);
        RECOVERY_FILE_SIZE.labels(serverName).set(0);
    }

    public void stop() {
        if (metricsTask != null) metricsTask.cancel();
    }

    private void updateStaticMetrics() {
        try {
            File recoveryFile = new File(plugin.getDataFolder(), "recovery.log");
            if (recoveryFile.exists()) {
                RECOVERY_FILE_SIZE.labels(serverName).set(recoveryFile.length());
            } else {
                RECOVERY_FILE_SIZE.labels(serverName).set(0);
            }
        } catch (Exception e) {
            LOGGER.error(e, "Unable to read the size of the file 'recovery.log'");
        }
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
