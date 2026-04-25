package tech.skworks.tachyon.plugin.spigot;

import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import tech.skworks.tachyon.api.TachyonAPI;
import tech.skworks.tachyon.api.profile.TachyonProfile;
import tech.skworks.tachyon.api.component.ComponentRegistry;
import tech.skworks.tachyon.api.profile.TachyonProfileRegistry;
import tech.skworks.tachyon.api.services.*;
import tech.skworks.tachyon.plugin.core.audit.GrpcAuditService;
import tech.skworks.tachyon.plugin.core.system.GrpcSystemService;
import tech.skworks.tachyon.plugin.spigot.command.SnapshotCommand;
import tech.skworks.tachyon.plugin.spigot.config.TachyonConfig;
import tech.skworks.tachyon.plugin.core.playersession.GrpcPlayerSessionService;
import tech.skworks.tachyon.plugin.core.snapshots.GrpcSnapshotService;
import tech.skworks.tachyon.plugin.common.util.TachyonLogger;
import tech.skworks.tachyon.plugin.core.grpc.BackendStubProvider;
import tech.skworks.tachyon.plugin.core.metric.MetricsService;
import tech.skworks.tachyon.plugin.core.component.ComponentRegistryImpl;
import tech.skworks.tachyon.plugin.core.playerdata.TachyonProfileRegistryImpl;
import tech.skworks.tachyon.plugin.spigot.listener.ConnectionListener;
import tech.skworks.tachyon.plugin.core.playerdata.GrpcPlayerDataService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Project Tachyon
 * Class TachyonCore
 *
 * @author Jimmy (vSKAH) - 05/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class TachyonCore extends JavaPlugin implements TachyonAPI<ItemStack> {

    private static TachyonCore instance;
    private TachyonLogger logger;
    private TachyonConfig config;

    private MetricsService metricsService;

    private ComponentRegistryImpl componentRegistryImpl;
    private TachyonProfileRegistry tachyonProfileRegistry;

    private BackendStubProvider backendStubProvider;
    private SystemService systemService;
    private GrpcAuditService grpcAuditService;
    private GrpcPlayerDataService grpcPlayerDataService;
    private GrpcPlayerSessionService grpcPlayerSessionService;
    private GrpcSnapshotService grpcSnapshotService;

    private boolean tachyonDisabling;

    @Override
    public void onEnable() {
        instance = this;
        this.tachyonDisabling = true;
        this.logger = new TachyonLogger(super.getLogger().getName(), "TachyonPlugin");
        this.saveDefaultConfig();
        this.config = TachyonConfig.fromFile(getConfig());

        this.metricsService = new MetricsService(config.serverName(), this);

        this.componentRegistryImpl = new ComponentRegistryImpl();
        this.tachyonProfileRegistry = new TachyonProfileRegistryImpl(componentRegistryImpl, metricsService.getTachyonMetrics());

        this.backendStubProvider = new BackendStubProvider(config.grpcHost(), config.grpcPort());
        this.systemService = new GrpcSystemService(metricsService.getTachyonMetrics(), backendStubProvider, config.serverName());
        this.logger.info("Testing connection to Quarkus Backend at {}:{}...", config.grpcHost(), config.grpcPort());

        try {
            if (!systemService.pingBackend().get(10, TimeUnit.SECONDS)) {
                errorShutdown();
                return;
            }
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            errorShutdown();
            throw new RuntimeException(e);
        }

        this.logger.info("Connection successful ! Backend is healthy.");

        this.grpcAuditService = new GrpcAuditService(metricsService.getTachyonMetrics(), backendStubProvider, config);
        this.grpcPlayerDataService = new GrpcPlayerDataService(backendStubProvider, getDataFolder(), metricsService.getTachyonMetrics());
        this.grpcPlayerSessionService = new GrpcPlayerSessionService(metricsService.getTachyonMetrics(), backendStubProvider, this);
        this.grpcSnapshotService = new GrpcSnapshotService(metricsService.getTachyonMetrics(), backendStubProvider);

        this.metricsService.startMetricsCollection(config.metricsConfig());

        getServer().getPluginManager().registerEvents(new ConnectionListener(this), this);
        registerCommand("snapshot", new SnapshotCommand(this));
        getServer().getServicesManager().register(TachyonAPI.class, this, this, ServicePriority.Highest);
        this.tachyonDisabling = false;
        this.logger.info("Tachyon Core [{}] initialized.", config.serverName());
    }

    @Override
    public void onDisable() {
        this.logger.info("Starting graceful shutdown...");
        this.tachyonDisabling = true;

        if (tachyonProfileRegistry != null) {
            final Collection<TachyonProfile> profiles = new ArrayList<>(tachyonProfileRegistry.getProfiles());
            final int count = profiles.size();

            logger.info("Saving {} loaded profile(s) before shutdown...", count);

            List<CompletableFuture<Void>> saveFutures = new ArrayList<>(count);

            for (TachyonProfile profile : profiles) {
                CompletableFuture<Void> safeSave = getPlayerDataService().pushProfile(profile)
                        .exceptionally(ex -> {
                            logger.error("saveProfile() failed at shutdown for {}: {}", profile.getUuid(), ex.getMessage());
                            return null;
                        });
                saveFutures.add(safeSave);
            }

            try {
                CompletableFuture.allOf(saveFutures.toArray(new CompletableFuture[0])).orTimeout(10, TimeUnit.SECONDS).join();
                logger.info("All profile saves completed.");
            } catch (Exception e) {
                logger.error(e, "Profile saves did not complete within 10s — proceeding with shutdown.");
                logger.error("Unsaved data may be recovered from the retry queue or dead-letter files.");
            }
        }

        if (grpcAuditService != null) grpcAuditService.shutdown();
        if (grpcPlayerDataService != null) grpcPlayerDataService.shutdown();
        if (grpcSnapshotService != null) grpcSnapshotService.shutdown();
        if (backendStubProvider != null) backendStubProvider.shutdown();
        if (metricsService != null) metricsService.shutdownMetricsCollection();
        logger.info("Tachyon Core disabled safely.");
    }

    public static TachyonLogger getModuleLogger(@NotNull final String moduleName) {
        return new TachyonLogger(instance.getLogger().getName(), moduleName);
    }

    private void registerCommand(@NotNull final String commandName, @NotNull TabExecutor command) {
        PluginCommand pluginCommand = getCommand(commandName);

        if (pluginCommand == null) {
            logger.error("Failed to register command '{}'!", commandName);
            return;
        }

        pluginCommand.setExecutor(command);
        pluginCommand.setTabCompleter(command);
    }

    private void errorShutdown() {
        logger.error("=====================================================");
        logger.error("[CRITICAL ERROR] Quarkus Backend is UNREACHABLE !");
        logger.error("Tachyon cannot start without its database.");
        logger.error("The plugin will now disable itself to prevent data corruption.");
        logger.error("=====================================================");
        getServer().getPluginManager().disablePlugin(this);
        getServer().shutdown();
    }

    @Override
    public TachyonProfileRegistry getTachyonProfileRegistry() {
        return tachyonProfileRegistry;
    }

    @Override
    public ComponentRegistry<ItemStack> getComponentRegistry() {
        return componentRegistryImpl;
    }

    @Override
    public SystemService getSystemService() {
        return systemService;
    }

    @Override
    public AuditService getAuditService() {
        return grpcAuditService;
    }

    @Override
    public SnapshotService getSnapshotService() {
        return grpcSnapshotService;
    }

    @Override
    public PlayerSessionService getPlayerSessionService() {
        return grpcPlayerSessionService;
    }

    @Override
    public PlayerDataService getPlayerDataService() {
        return grpcPlayerDataService;
    }

    @Override
    public boolean tachyonCoreDisabling() {
        return tachyonDisabling;
    }

    public TachyonConfig getPluginConfig() {
        return config;
    }
}
