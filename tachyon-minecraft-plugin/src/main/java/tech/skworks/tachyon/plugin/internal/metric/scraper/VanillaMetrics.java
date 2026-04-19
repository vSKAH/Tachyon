package tech.skworks.tachyon.plugin.internal.metric.scraper;

import com.sun.management.UnixOperatingSystemMXBean;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import me.lucko.spark.api.Spark;
import me.lucko.spark.api.SparkProvider;
import me.lucko.spark.api.statistic.StatisticWindow;
import me.lucko.spark.api.statistic.misc.DoubleAverageInfo;
import me.lucko.spark.api.statistic.types.DoubleStatistic;
import me.lucko.spark.api.statistic.types.GenericStatistic;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import tech.skworks.tachyon.plugin.plugin.TachyonCore;
import tech.skworks.tachyon.plugin.internal.util.TachyonLogger;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;


/**
 * Project Tachyon
 * Class VanillaMetrics
 *
 * @author  Jimmy (vSKAH) - 07/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class VanillaMetrics {

    private final String serverName;
    private final TachyonCore plugin;
    private BukkitTask metricsTask;
    private Handler errorAppender;
    private Spark spark;

    private static final TachyonLogger LOGGER = TachyonCore.getModuleLogger("VanillaMetrics");
    private static final Gauge TPS = Gauge.build().name("spigot_tps").help("Ticks par seconde (10s, 1m, 5m, 15m)").labelNames("server_name", "time_window").register();
    private static final Gauge MSPT = Gauge.build().name("spigot_mspt").help("Millisecondes par tick (moyenne, max, 95th percentile)").labelNames("server_name", "metric_type", "time_window").register();
    private static final Gauge CPU = Gauge.build().name("spigot_cpu_usage").help("Utilisation CPU (Processus et Système)").labelNames("server_name", "type", "time_window").register();
    private static final Gauge THREADS = Gauge.build().name("spigot_jvm_threads").help("Nombre de threads actifs dans la JVM").labelNames("server_name").register();
    private static final Gauge RAM = Gauge.build().name("spigot_memory_bytes").help("Utilisation de la RAM de la JVM").labelNames("server_name", "type").register();

    private static final Gauge GC_COUNT = Gauge.build().name("spigot_gc_collections_total").help("Nombre total de passages du Garbage Collector").labelNames("server_name", "gc_name").register();
    private static final Gauge GC_TIME = Gauge.build().name("spigot_gc_time_ms_total").help("Temps total passé dans le Garbage Collector en ms").labelNames("server_name", "gc_name").register();
    private static final Gauge GC_AVG_TIME = Gauge.build().name("spigot_gc_avg_time_ms").help("Temps moyen d'une collection GC").labelNames("server_name", "gc_name").register();
    private static final Gauge GC_AVG_FREQ = Gauge.build().name("spigot_gc_avg_frequency_ms").help("Fréquence moyenne des collections GC").labelNames("server_name", "gc_name").register();

    private static final Gauge TOTAL_PLAYERS = Gauge.build().name("spigot_players_total").help("Joueurs uniques").labelNames("server_name").register();
    private static final Gauge ONLINE_PLAYERS = Gauge.build().name("spigot_players_online").help("Joueurs connectés").labelNames("server_name").register();
    private static final Gauge CHUNKS = Gauge.build().name("spigot_chunks_loaded").help("Nombre de chunks chargés par monde").labelNames("server_name", "world_name").register();

    private static final Gauge ENTITIES = Gauge.build().name("spigot_entities_loaded").help("Nombre d'entités par monde").labelNames("server_name", "world_name").register();
    private static final Gauge WORLDS = Gauge.build().name("spigot_worlds_loaded").help("Monde chargés").labelNames("server_name").register();
    private static final Gauge PLUGINS = Gauge.build().name("spigot_plugins_loaded").help("Nombre de plugins installés et actifs").labelNames("server_name").register();
    private static final Gauge UPTIME = Gauge.build().name("spigot_jvm_uptime_ms").help("Temps depuis le démarrage du serveur (Uptime)").labelNames("server_name").register();
    private static final Gauge OPEN_FILES = Gauge.build().name("spigot_jvm_open_files").help("Nombre de descripteurs de fichiers ouverts (Sockets, Fichiers)").labelNames("server_name").register();

    private static final Counter CONSOLE_LOGS = Counter.build().name("spigot_console_logs_total").help("Compteur d'erreurs et alertes dans la console").labelNames("server_name", "level").register();

    public VanillaMetrics(String serverName, TachyonCore plugin) {
        this.serverName = serverName;
        this.plugin = plugin;
    }

    public void start() {
        if (Bukkit.getPluginManager().getPlugin("spark") == null) {
            LOGGER.warn("Spark plugin not found. Spark must be installed to use vanilla metrics.");
            return;
        }
        try {
            this.spark = SparkProvider.get();
            LOGGER.info("Successfully hooked into Spark Profiler API!");
        } catch (Exception e) {
            LOGGER.error(e, "Found Spark but failed to get API");
            return;
        }

        attachBukkitHandler();
        warmStats();
        this.metricsTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateMetrics, 100L, 100L);
    }


    private void warmStats() {
        ONLINE_PLAYERS.labels(serverName).set(0);
        TOTAL_PLAYERS.labels(serverName).set(0);
        WORLDS.labels(serverName).set(0);
        PLUGINS.labels(serverName).set(0);
        UPTIME.labels(serverName).set(0);
        OPEN_FILES.labels(serverName).set(0);
        THREADS.labels(serverName).set(0);

        RAM.labels(serverName, "max").set(0);
        RAM.labels(serverName, "free").set(0);
        RAM.labels(serverName, "used").set(0);

        for (String window : List.of("5s", "10s", "1m", "5m", "15m")) {
            TPS.labels(serverName, window).set(0);
        }
        for (String window : List.of("10s", "1m", "5m")) {
            for (String type : List.of("mean", "min", "max", "95th", "median")) {
                MSPT.labels(serverName, type, window).set(0);
            }
        }
        for (String type : List.of("process", "system")) {
            for (String window : List.of("10s", "1m", "15m")) {
                CPU.labels(serverName, type, window).set(0);
            }
        }

        CONSOLE_LOGS.labels(serverName, "WARN").inc(0);
        CONSOLE_LOGS.labels(serverName, "ERROR").inc(0);
        CONSOLE_LOGS.labels(serverName, "FATAL").inc(0);
    }


    public void stop() {
        if (metricsTask != null) metricsTask.cancel();
        detachBukkitHandler();
    }

    private void updateMetrics() {
        if (plugin.tachyonCoreDisabling()) return;
        final int onlinePlayerCount = Bukkit.getOnlinePlayers().size();
        final int uniquePlayerCount = Bukkit.getOfflinePlayers().length;

        final int worldsCount = Bukkit.getWorlds().size();
        final int pluginsCount = Bukkit.getPluginManager().getPlugins().length;

        int totalEntities = 0;
        int totalChunks = 0;
        for (World world : Bukkit.getWorlds()) {
            totalEntities += world.getEntities().size();
            totalChunks += world.getLoadedChunks().length;
        }

        final int finalEntities = totalEntities;
        final int finalChunks = totalChunks;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                ONLINE_PLAYERS.labels(serverName).set(onlinePlayerCount);
                TOTAL_PLAYERS.labels(serverName).set(uniquePlayerCount);

                ENTITIES.labels(serverName, "all_worlds").set(finalEntities);
                CHUNKS.labels(serverName, "all_worlds").set(finalChunks);
                WORLDS.labels(serverName).set(worldsCount);

                PLUGINS.labels(serverName).set(pluginsCount);
                UPTIME.labels(serverName).set(ManagementFactory.getRuntimeMXBean().getUptime());
                OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
                if (os instanceof UnixOperatingSystemMXBean linux) {
                    OPEN_FILES.labels(serverName).set(linux.getOpenFileDescriptorCount());
                }


                var tpsStat = spark.tps();
                if (tpsStat != null) {
                    getTpsStats(tpsStat);
                }

                var msptStat = spark.mspt();
                if (msptStat != null) {
                    getMsptStats(msptStat, StatisticWindow.MillisPerTick.SECONDS_10, "10s");
                    getMsptStats(msptStat, StatisticWindow.MillisPerTick.MINUTES_1, "1m");
                    getMsptStats(msptStat, StatisticWindow.MillisPerTick.MINUTES_5, "5m");
                }

                getCpuStats(spark.cpuProcess(), "process");
                getCpuStats(spark.cpuSystem(), "system");

                for (var entry : spark.gc().entrySet()) {
                    String gcName = entry.getKey().replace(" ", "_");
                    var gcStat = entry.getValue();

                    GC_COUNT.labels(serverName, gcName).set(gcStat.totalCollections());
                    GC_TIME.labels(serverName, gcName).set(gcStat.totalTime());
                    GC_AVG_TIME.labels(serverName, gcName).set(gcStat.avgTime());
                    long avgFreq = gcStat.avgFrequency();
                    if (avgFreq > 0) {
                        GC_AVG_FREQ.labels(serverName, gcName).set(avgFreq);
                    }
                }


                Runtime runtime = Runtime.getRuntime();
                RAM.labels(serverName, "max").set(runtime.maxMemory());
                RAM.labels(serverName, "free").set(runtime.freeMemory());
                RAM.labels(serverName, "used").set(runtime.totalMemory() - runtime.freeMemory());

                ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
                THREADS.labels(serverName).set(threadBean.getThreadCount());

            } catch (Exception e) {
                LOGGER.error(e,"Erreur critique dans le thread asynchrone des métriques");
            }
        });
    }

    private void getMsptStats(GenericStatistic<DoubleAverageInfo, StatisticWindow.MillisPerTick> msptStat, StatisticWindow.MillisPerTick time, String key) {
        var msptStats = msptStat.poll(time);
        MSPT.labels(serverName, "mean", key).set(msptStats.mean());
        MSPT.labels(serverName, "min", key).set(msptStats.min());
        MSPT.labels(serverName, "max", key).set(msptStats.max());
        MSPT.labels(serverName, "95th", key).set(msptStats.percentile95th());
        MSPT.labels(serverName, "median", key).set(msptStats.median());
    }

    private void getTpsStats(DoubleStatistic<StatisticWindow.TicksPerSecond> statistic) {
        TPS.labels(serverName, "5s").set(statistic.poll(StatisticWindow.TicksPerSecond.SECONDS_5));
        TPS.labels(serverName, "10s").set(statistic.poll(StatisticWindow.TicksPerSecond.SECONDS_10));
        TPS.labels(serverName, "1m").set(statistic.poll(StatisticWindow.TicksPerSecond.MINUTES_1));
        TPS.labels(serverName, "5m").set(statistic.poll(StatisticWindow.TicksPerSecond.MINUTES_5));
        TPS.labels(serverName, "15m").set(statistic.poll(StatisticWindow.TicksPerSecond.MINUTES_15));
    }

    private void getCpuStats(DoubleStatistic<StatisticWindow.CpuUsage> statistic, String key) {
        CPU.labels(serverName, key, "10s").set(statistic.poll(StatisticWindow.CpuUsage.SECONDS_10));
        CPU.labels(serverName, key, "1m").set(statistic.poll(StatisticWindow.CpuUsage.MINUTES_1));
        CPU.labels(serverName, key, "15m").set(statistic.poll(StatisticWindow.CpuUsage.MINUTES_15));
    }

    private void attachBukkitHandler() {
        errorAppender = new Handler() {
            @Override
            public void publish(LogRecord record) {
                Level level = record.getLevel();
                if (level == Level.SEVERE || level == Level.WARNING) {
                    CONSOLE_LOGS.labels(serverName, level.getName()).inc();
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() throws SecurityException {}
        };

        org.bukkit.Bukkit.getLogger().addHandler(errorAppender);
    }

    private void detachBukkitHandler() {
        if (errorAppender != null) {
            org.bukkit.Bukkit.getLogger().removeHandler(errorAppender);
        }
    }
}
