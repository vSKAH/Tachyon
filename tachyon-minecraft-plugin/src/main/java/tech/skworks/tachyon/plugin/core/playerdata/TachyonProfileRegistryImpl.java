package tech.skworks.tachyon.plugin.core.playerdata;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tech.skworks.tachyon.api.profile.TachyonProfile;
import tech.skworks.tachyon.api.profile.TachyonProfileRegistry;
import tech.skworks.tachyon.libs.com.google.protobuf.Any;
import tech.skworks.tachyon.libs.com.google.protobuf.Message;
import tech.skworks.tachyon.plugin.core.component.ComponentRegistryImpl;
import tech.skworks.tachyon.plugin.spigot.TachyonCore;
import tech.skworks.tachyon.plugin.common.util.TachyonLogger;
import tech.skworks.tachyon.plugin.core.metric.scraper.TachyonMetrics;
import tech.skworks.tachyon.service.contracts.player.data.PullProfileResponse;

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
public class TachyonProfileRegistryImpl implements TachyonProfileRegistry {

    private static final TachyonLogger LOGGER = TachyonCore.getModuleLogger("ProfileManager");

    private final Map<UUID, TachyonProfile> profiles = new ConcurrentHashMap<>();
    private final ComponentRegistryImpl componentRegistryImpl;

    @Nullable
    private final TachyonMetrics tachyonMetrics;

    public TachyonProfileRegistryImpl(@NotNull final ComponentRegistryImpl componentRegistryImpl, @Nullable TachyonMetrics tachyonMetrics) {
        this.componentRegistryImpl = componentRegistryImpl;
        this.tachyonMetrics = tachyonMetrics;
    }

    private void add(@NotNull final TachyonProfile profile) {
        this.profiles.put(profile.getUuid(), profile);
    }

    private void remove(@NotNull final UUID uuid) {
        this.profiles.remove(uuid);
    }

    @Override
    public void buildProfile(@NotNull final PullProfileResponse response, @NotNull final UUID uuid) {
        TachyonProfileImpl profile = new TachyonProfileImpl(uuid);

        int loaded = 0;
        int skipped = 0;

        for (Any any : response.getComponentsList()) {
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

    @Override
    public boolean profileIsLoaded(@NotNull final UUID uuid) {
        return this.profiles.containsKey(uuid);
    }

    @Override
    public void unloadProfile(@NotNull final UUID uuid) {
        remove(uuid);
        LOGGER.info("Profile unloaded for {}. Active profiles: {}", uuid, profiles.size());
        if (tachyonMetrics != null) tachyonMetrics.updateProfilesCount(profiles.size());
    }

    @Override
    public @Nullable TachyonProfile getProfile(@NotNull final UUID uuid) {
        return this.profiles.get(uuid);
    }

    @Override
    public @NotNull Collection<TachyonProfile> getProfiles() {
        return this.profiles.values();
    }

}
