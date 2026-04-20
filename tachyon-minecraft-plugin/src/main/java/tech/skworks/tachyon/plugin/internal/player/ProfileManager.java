package tech.skworks.tachyon.plugin.internal.player;

import tech.skworks.tachyon.service.contracts.player.GetPlayerResponse;
import tech.skworks.tachyon.libs.com.google.protobuf.Any;
import tech.skworks.tachyon.libs.com.google.protobuf.Message;
import tech.skworks.tachyon.plugin.spigot.TachyonCore;
import tech.skworks.tachyon.plugin.internal.player.component.ComponentRegistryImpl;
import tech.skworks.tachyon.plugin.internal.player.component.GrpcComponentService;
import tech.skworks.tachyon.plugin.internal.util.TachyonLogger;
import tech.skworks.tachyon.plugin.internal.metric.scraper.TachyonMetrics;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Project Tachyon
 * Class ProfileRegistry
 *
 * @author  Jimmy (vSKAH) - 06/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class ProfileManager {

    private static final TachyonLogger LOGGER = TachyonCore.getModuleLogger("ProfileManager");

    private final Map<UUID, GrpcTachyonProfile> profiles = new ConcurrentHashMap<>();
    private final GrpcComponentService grpcComponentService;
    private final ComponentRegistryImpl componentRegistryImpl;

    @Nullable
    private final TachyonMetrics tachyonMetrics;

    public ProfileManager(GrpcComponentService grpcComponentService, ComponentRegistryImpl componentRegistryImpl, @Nullable TachyonMetrics tachyonMetrics) {
        this.grpcComponentService = grpcComponentService;
        this.componentRegistryImpl = componentRegistryImpl;
        this.tachyonMetrics = tachyonMetrics;
    }

    private void add(GrpcTachyonProfile profile) {
        if (profile == null) return;
        this.profiles.put(profile.getUuid(), profile);
    }

    private void remove(UUID uuid) {
        this.profiles.remove(uuid);
    }

    public @Nullable GrpcTachyonProfile get(UUID uuid) {
        return this.profiles.get(uuid);
    }

    public Collection<GrpcTachyonProfile> getAll() {
        return this.profiles.values();
    }

    public boolean isLoaded(UUID uuid) {
        return this.profiles.containsKey(uuid);
    }

    public void load(GetPlayerResponse playerResponse, UUID uuid) {
        GrpcTachyonProfile profile = new GrpcTachyonProfile(uuid, grpcComponentService);

        int loaded  = 0;
        int skipped = 0;

        for (Any any : playerResponse.getComponentsList()) {
            Message message = componentRegistryImpl.unpack(any);

            if (message != null) {
                profile.initComponent(message);
                loaded++;
            } else {
                LOGGER.warn("Could not unpack component type '{}' for player {} — " + "is it registered via registry.register(...)?", any.getTypeUrl(), uuid);
                skipped++;
            }
        }

        LOGGER.info("Profile loaded for {} — {} component(s) loaded, {} skipped.", uuid, loaded, skipped);

        add(profile);
        if (tachyonMetrics != null) tachyonMetrics.updateProfilesCount(profiles.size());
    }

    public void unloadPlayer(UUID uuid) {
        remove(uuid);
        LOGGER.info("Profile unloaded for {}. Active profiles: {}", uuid, profiles.size());
        if (tachyonMetrics != null) tachyonMetrics.updateProfilesCount(profiles.size());
    }

}
