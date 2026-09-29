package tech.skworks.tachyon.plugin.spigot;

import com.tcoded.folialib.FoliaLib;
import dev.faststats.Metrics;
import dev.faststats.bukkit.BukkitContext;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import tech.skworks.tachyon.api.TachyonAPI;
import tech.skworks.tachyon.api.audit.AuditService;
import tech.skworks.tachyon.api.event.EventBus;
import tech.skworks.tachyon.api.profile.PlayerDataService;
import tech.skworks.tachyon.api.profile.TachyonProfile;
import tech.skworks.tachyon.api.component.ComponentRegistry;
import tech.skworks.tachyon.api.profile.TachyonProfileRegistry;
import tech.skworks.tachyon.api.services.*;
import tech.skworks.tachyon.api.snapshot.SnapshotService;
import tech.skworks.tachyon.api.system.PingResponse;
import tech.skworks.tachyon.api.system.HealthService;
import tech.skworks.tachyon.core.audit.GrpcAuditService;
import tech.skworks.tachyon.core.component.ComponentRegistryImpl;
import tech.skworks.tachyon.core.event.TachyonEventBusImpl;
import tech.skworks.tachyon.core.grpc.GrpcClient;
import tech.skworks.tachyon.core.metric.MetricsServiceImpl;
import tech.skworks.tachyon.core.playerdata.AutoSaveTask;
import tech.skworks.tachyon.core.playerdata.PlayerDataDlqService;
import tech.skworks.tachyon.core.playerdata.PlayerDataServiceImpl;
import tech.skworks.tachyon.core.playerdata.TachyonProfileRegistryImpl;
import tech.skworks.tachyon.core.playerdata.handoff.HandoffClientManager;
import tech.skworks.tachyon.core.playersession.PlayerSessionServiceImpl;
import tech.skworks.tachyon.core.resilience.ResilienceService;
import tech.skworks.tachyon.core.snapshots.GrpcSnapshotService;
import tech.skworks.tachyon.core.system.HealthServiceImpl;
import tech.skworks.tachyon.plugin.spigot.command.SnapshotCommand;
import tech.skworks.tachyon.plugin.spigot.config.TachyonConfig;
import tech.skworks.tachyon.plugin.spigot.listener.ConnectionListener;
import tech.skworks.tachyon.plugin.spigot.metrics.VanillaMetrics;
import tech.skworks.tachyon.plugin.spigot.ui.SnapshotUiService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;

/**
 * Project Tachyon
 * Class TachyonCore
 *
 * @author Jimmy (vSKAH) - 05/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class TachyonCore extends JavaPlugin implements TachyonAPI {


    private final BukkitContext fastStatsContext = new BukkitContext.Factory(this, "f72ccbdc64780bbf3be46ff22903de1f").metrics(Metrics.Factory::create).create();

    private TachyonConfig config;

    private MetricsServiceImpl metricsService;

    private ComponentRegistryImpl componentRegistry;
    private TachyonProfileRegistry tachyonProfileRegistry;

    private GrpcClient grpcClient;
    private HealthServiceImpl healthService;
    private GrpcAuditService auditService;
    private PlayerDataDlqService playerDataDlqService;
    private PlayerDataServiceImpl playerDataService;
    private PlayerSessionServiceImpl playerSessionService;
    private GrpcSnapshotService snapshotService;
    private ResilienceService resilienceService;
    private HandoffClientManager handoffClientManager;

    private TachyonEventBusImpl<JavaPlugin> eventBus;

    private ScheduledExecutorService autoSaveScheduler;

    private boolean tachyonDisabling;

    @Override
    public void onEnable() {
        this.tachyonDisabling = true;
        this.saveDefaultConfig();
        this.config = TachyonConfig.fromFile(getConfig());
        getLogger().info("Tachyon Config successfully loaded.");

        this.eventBus = new TachyonEventBusImpl<>();
        getLogger().info("Tachyon EventBus successfully initialized.");

        this.metricsService = new MetricsServiceImpl(config.serverName(), config.metricsConfig());

        this.componentRegistry = new ComponentRegistryImpl();
        this.tachyonProfileRegistry = new TachyonProfileRegistryImpl<>(componentRegistry, eventBus, metricsService.getTachyonCollector());

        this.resilienceService = new ResilienceService(metricsService.getTachyonCollector());

        this.grpcClient = GrpcClient.create(config.grpcClientConfig());
        this.healthService = new HealthServiceImpl(config.serverName(), resilienceService, grpcClient, metricsService.getTachyonCollector());
        if (!checkStartupBackendHealth()) return;
        this.auditService = new GrpcAuditService(config.serverName(), resilienceService, grpcClient, config.auditConfig(), metricsService.getTachyonCollector());
        this.playerDataDlqService = new PlayerDataDlqService(getDataFolder(), grpcClient);
        this.playerDataService = new PlayerDataServiceImpl(config.serverName(), resilienceService, grpcClient, playerDataDlqService, metricsService.getTachyonCollector());
        this.handoffClientManager = new HandoffClientManager(config.serverName(), grpcClient, tachyonProfileRegistry);
        this.playerSessionService = new PlayerSessionServiceImpl(config.serverName(), resilienceService, grpcClient, config.playerSessionConfig(), tachyonProfileRegistry, metricsService.getTachyonCollector());
        this.snapshotService = new GrpcSnapshotService(config.serverName(), resilienceService, grpcClient, componentRegistry, metricsService.getTachyonCollector());

        this.handoffClientManager.start();
        getLogger().info("Grpc Services have been initialized.");

        FoliaLib foliaLib = new FoliaLib(this);

        this.metricsService.getMetricsRegistry().add(new VanillaMetrics(metricsService.getMeterRegistry(), foliaLib));
        this.metricsService.startMetricsCollection();

        SnapshotUiService snapshotUiService = new SnapshotUiService(snapshotService, componentRegistry, foliaLib);

        getServer().getPluginManager().registerEvents(new ConnectionListener(this), this);
        registerCommand("snapshot", new SnapshotCommand(this, snapshotUiService));
        getServer().getServicesManager().register(TachyonAPI.class, this, this, ServicePriority.Highest);

        this.healthService.startHealthMonitoring();
        this.healthService.registerRecoveryCallback(playerDataDlqService::replayAll);
        this.healthService.registerHealthyProbeCallback(handoffClientManager::sendKeepAlive);
        this.tachyonDisabling = false;
        scheduleAutoSave();
        this.fastStatsContext.ready();
        getLogger().info(String.format("Tachyon Core [%s] initialized.", config.serverName()));
    }

    private boolean checkStartupBackendHealth() {
        getLogger().info(String.format("Testing connection to Quarkus Backend at %s:%s...", config.grpcClientConfig().getHost(), config.grpcClientConfig().getPort()));

        try {
            PingResponse ping = healthService.pingBackend().get(5, TimeUnit.SECONDS);
            if (ping == null || !ping.healthy()) {
                errorShutdown();
                return false;
            }
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            errorShutdown();
            throw new RuntimeException(e);
        }

        getLogger().info("Connection successful ! Backend is healthy.");
        return true;
    }

    @Override
    public void onDisable() {
        getLogger().info("Starting graceful shutdown...");
        this.fastStatsContext.shutdown();
        this.tachyonDisabling = true;

        if (autoSaveScheduler != null) {
            autoSaveScheduler.shutdown();
            try {
                if (!autoSaveScheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                    autoSaveScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                autoSaveScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        if (tachyonProfileRegistry != null) {
            final Collection<TachyonProfile> profiles = new ArrayList<>(tachyonProfileRegistry.getProfiles());
            final int count = profiles.size();

            getLogger().info(String.format("Saving %s loaded profile(s) before shutdown...", count));

            List<CompletableFuture<Void>> saveFutures = new ArrayList<>(count);

            for (TachyonProfile profile : profiles) {
                CompletableFuture<Void> safeSaveAndUnlock = getPlayerDataService().pushProfile(profile)
                        .thenRun(() -> getPlayerSessionService().unlockPlayerProfile(profile.getUuid()))
                        .exceptionally(ex -> {
                            getLogger().severe(String.format("saveProfile() failed at shutdown for %s: %s", profile.getUuid(), ex.getMessage()));
                            return null;
                        });
                saveFutures.add(safeSaveAndUnlock);
            }

            try {
                CompletableFuture.allOf(saveFutures.toArray(new CompletableFuture[0])).orTimeout(10, TimeUnit.SECONDS).join();
                getLogger().info("All profile saves completed.");
            } catch (Exception e) {
                getLogger().severe("Profile saves did not complete within 10s — proceeding with shutdown.");
                getLogger().severe("Unsaved data may be recovered from the retry queue or dead-letter files.");
                e.printStackTrace();
            }
        }

        if (this.eventBus != null) {
            this.eventBus.shutdown();
        }

        if (healthService != null) healthService.shutdown();
        if (handoffClientManager != null) handoffClientManager.shutdown();
        if (auditService != null) auditService.shutdown();
        if (playerDataService != null) playerDataService.shutdown();
        if (playerDataDlqService != null) playerDataDlqService.shutdown();
        if (snapshotService != null) snapshotService.shutdown();
        if (grpcClient != null) grpcClient.close();
        if (metricsService != null) metricsService.shutdownMetricsCollection();
        getLogger().info("Tachyon Core disabled.");
        Bukkit.getServicesManager().unregister(TachyonAPI.class, this);
    }


    private void scheduleAutoSave() {
        if (tachyonDisabling || tachyonProfileRegistry == null || playerDataService == null) return;

        final var playerDataConfig = config.playerDataConfig();
        if (!playerDataConfig.enableDataAutoSave()) {
            getLogger().info("Auto-save is disabled — profiles will only be persisted on quit/shutdown.");
            return;
        }

        final var delay = Math.max(2, playerDataConfig.dataAutoSaveDelay());
        this.autoSaveScheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("tachyon-autosave-executor").factory());

        this.autoSaveScheduler.scheduleAtFixedRate(
                new AutoSaveTask(this.tachyonProfileRegistry, this.playerDataService, this.playerDataDlqService, this.healthService),
                delay, delay, TimeUnit.SECONDS);

        getLogger().info(String.format("Auto-save enabled — flushing all dirty profiles every %ds.", playerDataConfig.dataAutoSaveDelay()));
    }

    private void registerCommand(@NotNull final String commandName, final @NotNull TabExecutor command) {
        PluginCommand pluginCommand = getCommand(commandName);

        if (pluginCommand == null) {
            getLogger().severe(String.join("Failed to register command '%s'!", commandName));
            return;
        }

        pluginCommand.setExecutor(command);
        pluginCommand.setTabCompleter(command);
    }

    private void errorShutdown() {
        final var logger = getLogger();
        logger.severe("=====================================================");
        logger.severe("[CRITICAL ERROR] Quarkus Backend is UNREACHABLE !");
        logger.severe("Tachyon cannot start without its database.");
        logger.severe("The plugin will now disable itself to prevent data corruption.");
        logger.severe("=====================================================");
        getServer().getPluginManager().disablePlugin(this);
        getServer().shutdown();
    }

    @Override
    public TachyonProfileRegistry getTachyonProfileRegistry() {
        return tachyonProfileRegistry;
    }

    @Override
    public ComponentRegistry getComponentRegistry() {
        return componentRegistry;
    }

    @Override
    public HealthService getHealthService() {
        return healthService;
    }

    @Override
    public AuditService getAuditService() {
        return auditService;
    }


    @Override
    public SnapshotService getSnapshotService() {
        return snapshotService;
    }

    @Override
    public PlayerSessionService getPlayerSessionService() {
        return playerSessionService;
    }

    @Override
    public PlayerDataService getPlayerDataService() {
        return playerDataService;
    }

    @Override
    public EventBus<JavaPlugin> getEventBus() {
        return eventBus;
    }

    @Override
    public boolean tachyonCoreDisabling() {
        return tachyonDisabling;
    }

    public TachyonConfig getPluginConfig() {
        return config;
    }

    public ResilienceService getResilienceService() {
        return resilienceService;
    }

    public PlayerDataDlqService getPlayerDataDlqService() {
        return playerDataDlqService;
    }

    public HandoffClientManager getHandoffClientManager() {
        return handoffClientManager;
    }
}
