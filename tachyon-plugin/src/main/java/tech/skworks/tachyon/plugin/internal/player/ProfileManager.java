package tech.skworks.tachyon.plugin.internal.player;

import tech.skworks.tachyon.libs.protobuf.Message;
import tech.skworks.tachyon.contracts.player.PlayerResponse;
import tech.skworks.tachyon.libs.protobuf.Any;
import tech.skworks.tachyon.plugin.Plugin;
import tech.skworks.tachyon.plugin.internal.player.component.ComponentRegistry;
import tech.skworks.tachyon.plugin.internal.player.component.ComponentService;
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

    private static final TachyonLogger LOGGER = Plugin.getModuleLogger("ProfileManager");

    private final Map<UUID, PlayerProfile> profiles = new ConcurrentHashMap<>();
    private final ComponentService componentService;
    private final ComponentRegistry componentRegistry;

    @Nullable
    private final TachyonMetrics tachyonMetrics;

    public ProfileManager(ComponentService componentService, ComponentRegistry componentRegistry, @Nullable TachyonMetrics tachyonMetrics) {
        this.componentService = componentService;
        this.componentRegistry = componentRegistry;
        this.tachyonMetrics = tachyonMetrics;
    }

    private void add(PlayerProfile profile) {
        if (profile == null) return;
        this.profiles.put(profile.getUuid(), profile);
    }

    private void remove(UUID uuid) {
        this.profiles.remove(uuid);
    }

    public @Nullable PlayerProfile get(UUID uuid) {
        return this.profiles.get(uuid);
    }

    public Collection<PlayerProfile> getAll() {
        return this.profiles.values();
    }

    public boolean isLoaded(UUID uuid) {
        return this.profiles.containsKey(uuid);
    }


    public void load(PlayerResponse playerResponse, UUID uuid) {
        PlayerProfile profile = new PlayerProfile(uuid, componentService);

        int loaded  = 0;
        int skipped = 0;

        for (Any any : playerResponse.getComponentsList()) {
            Message message = componentRegistry.unpack(any);

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
