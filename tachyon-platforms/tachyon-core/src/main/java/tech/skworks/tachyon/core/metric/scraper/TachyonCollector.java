package tech.skworks.tachyon.core.metric.scraper;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.skworks.tachyon.api.metrics.MetricsCollector;

import java.util.Map;
import java.util.Objects;
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
//TODO: rework this
public class TachyonCollector extends MetricsCollector {

    private final MeterRegistry meterRegistry;
    private final ScheduledExecutorService scheduler;

    private static final Logger LOGGER = LoggerFactory.getLogger("Tachyon/TachyonMetrics");

    private final AtomicInteger profilesCount = new AtomicInteger(0);
    private final AtomicLong recoveryFileBytes = new AtomicLong(0);
    private final AtomicInteger recoveryPendingFiles = new AtomicInteger(0);

    private final Map<String, Timer> grpcTimers = new ConcurrentHashMap<>();
    private final Map<String, Counter> grpcErrorCounters = new ConcurrentHashMap<>();

    public TachyonCollector(@NotNull final MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry cannot be null");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("scheduler-tachyon-metrics").factory());
    }

    @Override
    public void start() {
        Gauge.builder("tachyon_plugin_profiles_cached", profilesCount::get)
                .register(meterRegistry);

        Gauge.builder("tachyon_plugin_recovery_file_bytes", recoveryFileBytes::get)
                .register(meterRegistry);

        Gauge.builder("tachyon_plugin_recovery_pending_files", recoveryPendingFiles::get)
                .register(meterRegistry);

        this.scheduler.scheduleAtFixedRate(this::updateMetrics, 4, 4, TimeUnit.SECONDS);
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
        Timer.Sample sample = Timer.start(meterRegistry);
        return () -> sample.stop(getOrCreateTimer(method));
    }

    public MetricTimer startProfileLoadTimer() {
        Timer.Sample sample = Timer.start(meterRegistry);
        return () -> sample.stop(
                Timer.builder("tachyon_plugin_profile_total_load_seconds")
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .register(meterRegistry)
        );
    }

    public void recordPlayerLockRetry() {
        Counter.builder("tachyon_plugin_player_locked_retries_total")
                .register(meterRegistry)
                .increment();
    }

    public void recordPlayerLockExhausted() {
        Counter.builder("tachyon_plugin_player_locked_exhausted_total")
                .register(meterRegistry)
                .increment();
    }

    public void recordGrpcError(String method, String errorType) {
        String key = method + ":" + errorType;
        grpcErrorCounters.computeIfAbsent(key, _ ->
                Counter.builder("tachyon_plugin_grpc_errors_total")
                        .tag("method", method)
                        .tag("error_type", errorType)
                        .register(meterRegistry)
        ).increment();
    }

    public void updateProfilesCount(int count) {
        profilesCount.set(count);
    }

    private Timer getOrCreateTimer(String method) {
        return grpcTimers.computeIfAbsent(method, m ->
                Timer.builder("tachyon_plugin_grpc_latency_seconds")
                        .tag("method", m)
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .register(meterRegistry)
        );
    }
}
