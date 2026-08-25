package tech.skworks.tachyon.plugin.core.metric.scraper;

import com.sun.management.UnixOperatingSystemMXBean;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.FileDescriptorMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;
import me.lucko.spark.api.Spark;
import me.lucko.spark.api.SparkProvider;
import me.lucko.spark.api.statistic.StatisticWindow;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import tech.skworks.tachyon.api.metrics.MetricsCollector;
import tech.skworks.tachyon.plugin.spigot.TachyonCore;
import tech.skworks.tachyon.plugin.common.util.TachyonLogger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * High-performance Vanilla and Spark metrics collector using Micrometer.
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @author Jimmy (vSKAH) - 07/04/2026
 * @version 2.0
 * @since 1.0.0-SNAPSHOT
 */
public class VanillaMetrics extends MetricsCollector {

    private final TachyonCore plugin;
    private final MeterRegistry registry;
    private Handler errorAppender;
    private Spark spark;

    private static final TachyonLogger LOGGER = TachyonCore.getModuleLogger("VanillaMetrics");
    private static long totalPlayers = 0;

    private JvmGcMetrics jvmGcMetrics;
    private final Map<String, Counter> logCounters = new ConcurrentHashMap<>();

    public VanillaMetrics(@NotNull String serverName, TachyonCore plugin, MeterRegistry meterRegistry) {
        super(serverName);
        this.plugin = plugin;
        this.registry = meterRegistry;
    }

    @Override
    public void start() {
        new JvmMemoryMetrics(Tags.of("server_name", serverName)).bindTo(registry);
        this.jvmGcMetrics = new JvmGcMetrics(Tags.of("server_name", serverName));
        this.jvmGcMetrics.bindTo(registry);
        new JvmThreadMetrics(Tags.of("server_name", serverName)).bindTo(registry);
        new ProcessorMetrics(Tags.of("server_name", serverName)).bindTo(registry);
        new FileDescriptorMetrics(Tags.of("server_name", serverName)).bindTo(registry);
        new UptimeMetrics(Tags.of("server_name", serverName)).bindTo(registry);

        totalPlayers = Bukkit.getOfflinePlayers().length;

        Gauge.builder("spigot_players_online", Bukkit.getOnlinePlayers()::size)
                .tag("server_name", serverName)
                .register(registry);

        Gauge.builder("spigot_players_total", () -> totalPlayers)
                .tag("server_name", serverName)
                .register(registry);

        Gauge.builder("spigot_worlds_loaded", () -> Bukkit.getWorlds().size())
                .tag("server_name", serverName)
                .register(registry);

        Gauge.builder("spigot_plugins_loaded", () -> Bukkit.getPluginManager().getPlugins().length)
                .tag("server_name", serverName)
                .register(registry);


        Gauge.builder("spigot_chunks_loaded", this::getTotalLoadedChunks)
                .tags("server_name", serverName, "world_name", "all_worlds")
                .register(registry);

        Gauge.builder("spigot_entities_loaded", this::getTotalEntities)
                .tags("server_name", serverName, "world_name", "all_worlds")
                .register(registry);

        if (Bukkit.getPluginManager().getPlugin("spark") == null) {
            LOGGER.warn("Spark plugin not found. Spark metrics will not be available.");
        } else {
            try {
                this.spark = SparkProvider.get();
                registerSparkMetrics();
                LOGGER.info("Successfully hooked into Spark Profiler API!");
            } catch (Exception e) {
                LOGGER.error(e, "Found Spark but failed to register Spark metrics");
            }
        }

        attachBukkitHandler();
    }

    private void registerSparkMetrics() {
        registerTpsGauge("5s", StatisticWindow.TicksPerSecond.SECONDS_5);
        registerTpsGauge("10s", StatisticWindow.TicksPerSecond.SECONDS_10);
        registerTpsGauge("1m", StatisticWindow.TicksPerSecond.MINUTES_1);
        registerTpsGauge("5m", StatisticWindow.TicksPerSecond.MINUTES_5);
        registerTpsGauge("15m", StatisticWindow.TicksPerSecond.MINUTES_15);

        for (StatisticWindow.MillisPerTick window : List.of(StatisticWindow.MillisPerTick.SECONDS_10, StatisticWindow.MillisPerTick.MINUTES_1, StatisticWindow.MillisPerTick.MINUTES_5)) {
            String windowStr = window == StatisticWindow.MillisPerTick.SECONDS_10 ? "10s" : (window == StatisticWindow.MillisPerTick.MINUTES_1 ? "1m" : "5m");
            registerMsptGauge(window, windowStr, "mean");
            registerMsptGauge(window, windowStr, "min");
            registerMsptGauge(window, windowStr, "max");
            registerMsptGauge(window, windowStr, "95th");
            registerMsptGauge(window, windowStr, "median");
        }

        for (StatisticWindow.CpuUsage window : List.of(StatisticWindow.CpuUsage.SECONDS_10, StatisticWindow.CpuUsage.MINUTES_1, StatisticWindow.CpuUsage.MINUTES_15)) {
            String windowStr = window == StatisticWindow.CpuUsage.SECONDS_10 ? "10s" : (window == StatisticWindow.CpuUsage.MINUTES_1 ? "1m" : "15m");
            Gauge.builder("spigot_cpu_usage", () -> spark != null && spark.cpuProcess() != null ? spark.cpuProcess().poll(window) : 0.0)
                    .tags("server_name", serverName, "type", "process", "time_window", windowStr)
                    .register(registry);

            Gauge.builder("spigot_cpu_usage", () -> spark != null && spark.cpuSystem() != null ? spark.cpuSystem().poll(window) : 0.0)
                    .tags("server_name", serverName, "type", "system", "time_window", windowStr)
                    .register(registry);
        }

        if (spark != null && spark.gc() != null) {
            for (String gcKey : spark.gc().keySet()) {
                String gcName = gcKey.replace(" ", "_");

                Gauge.builder("spigot_gc_collections_total", () -> {
                    var gc = spark != null && spark.gc() != null ? spark.gc().get(gcKey) : null;
                    return gc != null ? gc.totalCollections() : 0;
                }).tags("server_name", serverName, "gc_name", gcName).register(registry);

                Gauge.builder("spigot_gc_time_ms_total", () -> {
                    var gc = spark != null && spark.gc() != null ? spark.gc().get(gcKey) : null;
                    return gc != null ? gc.totalTime() : 0;
                }).tags("server_name", serverName, "gc_name", gcName).register(registry);

                Gauge.builder("spigot_gc_avg_time_ms", () -> {
                    var gc = spark != null && spark.gc() != null ? spark.gc().get(gcKey) : null;
                    return gc != null ? gc.avgTime() : 0.0;
                }).tags("server_name", serverName, "gc_name", gcName).register(registry);

                Gauge.builder("spigot_gc_avg_frequency_ms", () -> {
                    var gc = spark != null && spark.gc() != null ? spark.gc().get(gcKey) : null;
                    return gc != null ? gc.avgFrequency() : 0;
                }).tags("server_name", serverName, "gc_name", gcName).register(registry);
            }
        }
    }

    private void registerTpsGauge(String windowStr, StatisticWindow.TicksPerSecond window) {
        Gauge.builder("spigot_tps", () -> spark != null && spark.tps() != null ? spark.tps().poll(window) : 20.0)
                .tags("server_name", serverName, "time_window", windowStr)
                .register(registry);
    }

    private void registerMsptGauge(StatisticWindow.MillisPerTick window, String windowStr, String type) {
        Gauge.builder("spigot_mspt", () -> {
            if (spark == null || spark.mspt() == null) return 0.0;
            var stat = spark.mspt().poll(window);
            if (stat == null) return 0.0;
            return switch (type) {
                case "mean" -> stat.mean();
                case "min" -> stat.min();
                case "max" -> stat.max();
                case "95th" -> stat.percentile95th();
                case "median" -> stat.median();
                default -> 0.0;
            };
        }).tags("server_name", serverName, "metric_type", type, "time_window", windowStr).register(registry);
    }

    private double getTotalLoadedChunks() {
        int chunks = 0;
        for (World world : Bukkit.getWorlds()) {
            chunks += world.getLoadedChunks().length;
        }
        return chunks;
    }

    private double getTotalEntities() {
        int entities = 0;
        for (World world : Bukkit.getWorlds()) {
            entities += world.getEntities().size();
        }
        return entities;
    }

    private void attachBukkitHandler() {
        errorAppender = new Handler() {
            @Override
            public void publish(LogRecord record) {
                Level level = record.getLevel();
                if (level == Level.SEVERE || level == Level.WARNING) {
                    String levelName = level.getName();
                    logCounters.computeIfAbsent(levelName, l ->
                            Counter.builder("spigot_console_logs_total")
                                    .tag("server_name", serverName)
                                    .tag("level", l)
                                    .register(registry)
                    ).increment();
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() throws SecurityException {}
        };

        Bukkit.getLogger().addHandler(errorAppender);
    }

    private void detachBukkitHandler() {
        if (errorAppender != null) {
            Bukkit.getLogger().removeHandler(errorAppender);
        }
    }

    @Override
    public void updateMetrics() {}

    @Override
    public void stop() {
        if (jvmGcMetrics != null) {
            jvmGcMetrics.close();
            jvmGcMetrics = null;
        }
        detachBukkitHandler();
    }

    public static void incrementTotalPlayers() {
        totalPlayers++;
    }
}
