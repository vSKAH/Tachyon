package tech.skworks.tachyon.plugin.core.metric.scraper;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.jetbrains.annotations.NotNull;
import tech.skworks.tachyon.api.metrics.MetricsCollector;
import tech.skworks.tachyon.plugin.spigot.TachyonCore;
import tech.skworks.tachyon.plugin.common.util.RecoveryLayout;
import tech.skworks.tachyon.plugin.common.util.TachyonLogger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance Tachyon gRPC and resilience metrics collector using Micrometer.
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @author Jimmy (vSKAH) - 08/04/2026
 * @version 2.0
 * @since 1.0.0-SNAPSHOT
 */
public class TachyonMetrics extends MetricsCollector {

    private final Path datafolder;
    private final MeterRegistry registry;
    private final ScheduledExecutorService scheduler;

    private static final TachyonLogger LOGGER = TachyonCore.getModuleLogger("TachyonMetrics");

    private final AtomicInteger profilesCount = new AtomicInteger(0);
    private final AtomicInteger retryQueueTasks = new AtomicInteger(0);
    private final AtomicLong recoveryFileBytes = new AtomicLong(0);
    private final AtomicInteger recoveryPendingFiles = new AtomicInteger(0);

    private final Map<String, Timer> grpcTimers = new ConcurrentHashMap<>();
    private final Map<String, Counter> grpcErrorCounters = new ConcurrentHashMap<>();

    public TachyonMetrics(@NotNull final String serverName, @NotNull final Path datafolder, @NotNull final MeterRegistry registry) {
        super(serverName);
        this.datafolder = datafolder;
        this.registry = registry;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("scheduler-tachyon-metrics").factory());
    }

    @Override
    public void start() {
        Gauge.builder("tachyon_plugin_profiles_cached", profilesCount::get)
                .tag("server_name", serverName)
                .register(registry);

        Gauge.builder("tachyon_plugin_retry_queue_tasks", retryQueueTasks::get)
                .tag("server_name", serverName)
                .register(registry);

        Gauge.builder("tachyon_plugin_recovery_file_bytes", recoveryFileBytes::get)
                .tag("server_name", serverName)
                .register(registry);

        Gauge.builder("tachyon_plugin_recovery_pending_files", recoveryPendingFiles::get)
                .tag("server_name", serverName)
                .register(registry);

        this.scheduler.scheduleAtFixedRate(this::updateMetrics, 4, 4, TimeUnit.SECONDS);
    }

    @Override
    public void updateMetrics() {
        try {
            Path dataDir = RecoveryLayout.dataDir(datafolder);

            if (!Files.isDirectory(dataDir)) {
                recoveryFileBytes.set(0);
                recoveryPendingFiles.set(0);
                return;
            }

            long totalSize = 0;
            int fileCount = 0;

            try (var stream = Files.newDirectoryStream(dataDir, RecoveryLayout.BIN_GLOB)) {
                for (Path file : stream) {
                    totalSize += Files.size(file);
                    fileCount++;
                }
            }

            recoveryFileBytes.set(totalSize);
            recoveryPendingFiles.set(fileCount);

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
    }

    @FunctionalInterface
    public interface MetricTimer extends AutoCloseable {
        @Override
        void close();
    }

    public MetricTimer startGrpcTimer(String method) {
        Timer.Sample sample = Timer.start(registry);
        return () -> sample.stop(getOrCreateTimer(method));
    }

    public MetricTimer startProfileLoadTimer() {
        Timer.Sample sample = Timer.start(registry);
        return () -> sample.stop(
                Timer.builder("tachyon_plugin_profile_total_load_seconds")
                        .tag("server_name", serverName)
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .register(registry)
        );
    }

    public void recordPlayerLockRetry() {
        Counter.builder("tachyon_plugin_player_locked_retries_total")
                .tag("server_name", serverName)
                .register(registry)
                .increment();
    }

    public void recordPlayerLockExhausted() {
        Counter.builder("tachyon_plugin_player_locked_exhausted_total")
                .tag("server_name", serverName)
                .register(registry)
                .increment();
    }

    public void recordGrpcError(String method, String errorType) {
        String key = method + ":" + errorType;
        grpcErrorCounters.computeIfAbsent(key, _ ->
                Counter.builder("tachyon_plugin_grpc_errors_total")
                        .tag("server_name", serverName)
                        .tag("method", method)
                        .tag("error_type", errorType)
                        .register(registry)
        ).increment();
    }

    public void updateProfilesCount(int count) {
        profilesCount.set(count);
    }

    public void updateRetryQueueSize(int totalTasks) {
        retryQueueTasks.set(totalTasks);
    }

    private Timer getOrCreateTimer(String method) {
        return grpcTimers.computeIfAbsent(method, m ->
                Timer.builder("tachyon_plugin_grpc_latency_seconds")
                        .tag("server_name", serverName)
                        .tag("method", m)
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .register(registry)
        );
    }
}
