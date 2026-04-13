package tech.skworks.tachyon.plugin;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import tech.skworks.tachyon.plugin.internal.audit.AuditManager;
import tech.skworks.tachyon.plugin.internal.config.TachyonConfig;
import tech.skworks.tachyon.plugin.internal.player.profile.HeartBeatsTask;
import tech.skworks.tachyon.plugin.internal.player.profile.PlayerProfileService;
import tech.skworks.tachyon.plugin.internal.util.TachyonLogger;
import tech.skworks.tachyon.plugin.internal.grpc.GrpcClientManager;
import tech.skworks.tachyon.plugin.internal.metric.MetricsService;
import tech.skworks.tachyon.plugin.internal.player.component.ComponentRegistry;
import tech.skworks.tachyon.plugin.internal.player.ProfileManager;
import tech.skworks.tachyon.plugin.internal.player.component.ConnectionListener;
import tech.skworks.tachyon.plugin.internal.player.PlayerProfile;
import tech.skworks.tachyon.plugin.internal.player.component.ComponentService;

/**
 * Project Tachyon
 * Class TachyonCore
 *
 * @author Jimmy (vSKAH) - 05/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class Plugin extends JavaPlugin {

    private static Plugin instance;
    private TachyonConfig config;

    private MetricsService metricsService;
    private GrpcClientManager grpcClient;
    private AuditManager auditManager;
    private ComponentService componentService;
    private ProfileManager profileManager;
    private ComponentRegistry componentRegistry;
    private TachyonLogger logger;
    private PlayerProfileService playerProfileService;
    private boolean tachyonDisabling;

    @Override
    public void onEnable() {
        instance = this;
        tachyonDisabling = false;
        saveDefaultConfig();
        config = TachyonConfig.fromFile(getConfig());
        logger = new TachyonLogger(super.getLogger().getName(), "TachyonPlugin");

        String serverName = getConfig().getString("server-name", "lobby-01");
        String host = getConfig().getString("grpc.host", "localhost");
        int port = getConfig().getInt("grpc.port", 9000);

        this.metricsService = new MetricsService(config.serverName(), this);
        this.grpcClient = new GrpcClientManager(host, port);

        logger.info("Testing connection to Quarkus Backend at " + host + ":" + port + "...");

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

        this.componentRegistry = new ComponentRegistry();
        this.componentService = new ComponentService(grpcClient, getDataFolder(), metricsService.getTachyonMetrics());
        this.profileManager = new ProfileManager(componentService, componentRegistry, metricsService.getTachyonMetrics());
        this.auditManager = new AuditManager(grpcClient, serverName);
        this.playerProfileService = new PlayerProfileService(metricsService.getTachyonMetrics(), grpcClient);
        Bukkit.getScheduler().runTaskTimer(this, new HeartBeatsTask(playerProfileService), 100, 100);


        getServer().getPluginManager().registerEvents(new ConnectionListener(profileManager, auditManager, componentService, grpcClient), this);

        metricsService.startMetricsCollection(config.metricsHost(), config.metricsPort());
        logger.info("Tachyon Core [" + serverName + "] initialized.");
    }

    @Override
    public void onDisable() {
        logger.info("Starting graceful shutdown...");
        tachyonDisabling = true;
        if (profileManager != null) {
            int count = profileManager.getAll().size();
            logger.info("Saving {} loaded profile(s) before shutdown...", count);
            for (PlayerProfile profile : profileManager.getAll()) {
                profile.saveProfile();
            }
        }

        if (auditManager != null) auditManager.shutdown();
        if (componentService != null) componentService.shutdown();
        if (grpcClient != null) grpcClient.shutdown();
        if (metricsService != null) metricsService.shutdownMetricsCollection();
        logger.info("Tachyon Core disabled safely.");
    }

    public ProfileManager getProfileManager() {
        return profileManager;
    }

    public AuditManager getAuditManager() {
        return auditManager;
    }

    public ComponentRegistry getComponentRegistry() {
        return componentRegistry;
    }

    public boolean isTachyonDisabling() {
        return tachyonDisabling;
    }

    public static TachyonLogger getModuleLogger(String moduleName) {
        return new TachyonLogger(instance.getLogger().getName(), moduleName);
    }
}
