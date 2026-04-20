package tech.skworks.tachyon.plugin.spigot;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tech.skworks.tachyon.api.TachyonAPI;
import tech.skworks.tachyon.api.profile.TachyonProfile;
import tech.skworks.tachyon.api.registries.ComponentRegistry;
import tech.skworks.tachyon.api.services.AuditService;
import tech.skworks.tachyon.api.services.SnapshotService;
import tech.skworks.tachyon.plugin.internal.audit.GrpcAuditService;
import tech.skworks.tachyon.plugin.spigot.command.SnapshotCommand;
import tech.skworks.tachyon.plugin.spigot.config.TachyonConfig;
import tech.skworks.tachyon.plugin.internal.player.heartbeat.HeartBeatsTask;
import tech.skworks.tachyon.plugin.internal.player.heartbeat.HeartBeatService;
import tech.skworks.tachyon.plugin.internal.snapshots.GrpcSnapshotService;
import tech.skworks.tachyon.plugin.internal.util.TachyonLogger;
import tech.skworks.tachyon.plugin.internal.GrpcClientManager;
import tech.skworks.tachyon.plugin.internal.metric.MetricsService;
import tech.skworks.tachyon.plugin.internal.player.component.ComponentRegistryImpl;
import tech.skworks.tachyon.plugin.internal.player.ProfileManager;
import tech.skworks.tachyon.plugin.spigot.listener.ConnectionListener;
import tech.skworks.tachyon.plugin.internal.player.component.GrpcComponentService;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
    private TachyonConfig config;

    private MetricsService metricsService;
    private GrpcClientManager grpcClient;

    private GrpcAuditService grpcAuditService;
    private GrpcComponentService grpcComponentService;
    private ProfileManager profileManager;
    private ComponentRegistryImpl componentRegistryImpl;
    private GrpcSnapshotService grpcSnapshotService;

    private TachyonLogger logger;
    private HeartBeatService heartBeatService;
    private boolean tachyonDisabling;

    @Override
    public void onEnable() {
        instance = this;
        tachyonDisabling = false;
        saveDefaultConfig();
        config = TachyonConfig.fromFile(getConfig());
        logger = new TachyonLogger(super.getLogger().getName(), "TachyonPlugin");

        this.metricsService = new MetricsService(config.serverName(), this);
        this.grpcClient = new GrpcClientManager(config.grpcHost(), config.grpcPort());

        logger.info("Testing connection to Quarkus Backend at {}:{}...", config.grpcHost(), config.grpcPort());

        if (!this.grpcClient.pingBackend()) {
            logger.error("=====================================================");
            logger.error("[CRITICAL ERROR] Quarkus Backend is UNREACHABLE !");
            logger.error("Tachyon cannot start without its database.");
            logger.error("The plugin will now disable itself to prevent data corruption.");
            logger.error("=====================================================");
            getServer().getPluginManager().disablePlugin(this);
            getServer().shutdown();
            return;
        }

        logger.info("Connection successful ! Backend is healthy.");

        this.componentRegistryImpl = new ComponentRegistryImpl();
        this.grpcComponentService = new GrpcComponentService(grpcClient, getDataFolder(), metricsService.getTachyonMetrics());
        this.profileManager = new ProfileManager(grpcComponentService, componentRegistryImpl, metricsService.getTachyonMetrics());
        this.grpcAuditService = new GrpcAuditService(grpcClient, config.serverName());
        this.grpcSnapshotService = new GrpcSnapshotService(metricsService.getTachyonMetrics(), grpcClient);
        this.heartBeatService = new HeartBeatService(metricsService.getTachyonMetrics(), grpcClient);
        Bukkit.getScheduler().runTaskTimer(this, new HeartBeatsTask(heartBeatService, config), 100, 120);

        getServer().getPluginManager().registerEvents(new ConnectionListener(profileManager, heartBeatService, grpcAuditService, grpcComponentService), this);

        registerCommand("snapshot", new SnapshotCommand(this));

        metricsService.startMetricsCollection(config.metricsHost(), config.metricsPort());
        getServer().getServicesManager().register(TachyonAPI.class, this, this, ServicePriority.Highest);
        logger.info("Tachyon Core [{}] initialized.", config.serverName());
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

    @Override
    public void onDisable() {
        logger.info("Starting graceful shutdown...");
        tachyonDisabling = true;
        if (profileManager != null) {
            int count = profileManager.getAll().size();
            logger.info("Saving {} loaded profile(s) before shutdown...", count);

            List<CompletableFuture<Void>> saveFutures = new ArrayList<>(count);
            for (TachyonProfile profile : profileManager.getAll()) {
                CompletableFuture<Void> safeSave = profile.saveProfile()
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
        if (grpcComponentService != null) grpcComponentService.shutdown();
        if (grpcSnapshotService != null) grpcSnapshotService.shutdown();
        if (grpcClient != null) grpcClient.shutdown();
        if (metricsService != null) metricsService.shutdownMetricsCollection();
        logger.info("Tachyon Core disabled safely.");
    }

    public static TachyonLogger getModuleLogger(@NotNull final String moduleName) {
        return new TachyonLogger(instance.getLogger().getName(), moduleName);
    }


    @Override
    public ComponentRegistry<ItemStack> getComponentRegistry() {
        return componentRegistryImpl;
    }

    @Override
    public @Nullable TachyonProfile getProfile(@NotNull final UUID uuid) {
        return profileManager.get(uuid);
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
    public boolean tachyonCoreDisabling() {
        return tachyonDisabling;
    }
}
