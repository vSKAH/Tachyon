package tech.skworks.tachyon.core.playerdata;

import org.bson.BsonDocument;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.skworks.tachyon.api.component.ComponentNamespace;
import tech.skworks.tachyon.api.component.ComponentRegistry;
import tech.skworks.tachyon.api.event.EventBus;
import tech.skworks.tachyon.api.event.profile.ProfileLoadedEvent;
import tech.skworks.tachyon.api.event.profile.ProfileUnloadedEvent;
import tech.skworks.tachyon.api.profile.TachyonProfile;
import tech.skworks.tachyon.api.profile.TachyonProfileRegistry;
import tech.skworks.tachyon.common.contract.PlayerDataContract;
import tech.skworks.tachyon.core.metric.scraper.TachyonCollector;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
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
public class TachyonProfileRegistryImpl<PLUGIN> implements TachyonProfileRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tachyon/ProfileRegistry");

    private final Map<UUID, TachyonProfile> profiles;
    private final ComponentRegistry componentRegistry;
    private final EventBus<PLUGIN> eventBus;

    @Nullable
    private final TachyonCollector tachyonCollector;

    public TachyonProfileRegistryImpl(@NotNull final ComponentRegistry componentRegistry,
                                      @NotNull final EventBus<PLUGIN> eventBus,
                                      @Nullable final TachyonCollector tachyonCollector) {
        this.componentRegistry = Objects.requireNonNull(componentRegistry, "componentRegistry cannot be null");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus cannot be null");
        this.tachyonCollector = tachyonCollector;
        this.profiles = new ConcurrentHashMap<>();

    }

    private void add(@NotNull final TachyonProfile profile) {
        this.profiles.put(profile.getUuid(), profile);
    }

    private @Nullable TachyonProfile remove(@NotNull final UUID uuid) {
        return this.profiles.remove(uuid);
    }

    //TODO: merge it with player Data entry system
    @Override
    public void buildProfile(@NotNull final BsonDocument response, @NotNull final UUID uuid) {
        final var profile = new TachyonProfileImpl(uuid, componentRegistry);
        final var componentsField = PlayerDataContract.FIELD_COMPONENTS;
        final var versionsField = PlayerDataContract.FIELD_VERSIONS;

        var loaded = 0;
        var skipped = 0;


        if (!response.containsKey(componentsField) || !response.isDocument(componentsField)) {
            LOGGER.error("Unable to find the '{}' section inside the response Document.", componentsField);
            return;
        }

        if (!response.containsKey(versionsField) || !response.isDocument(versionsField)) {
            LOGGER.error("Unable to find the '{}' section inside the response Document.", versionsField);
        }

        final var componentsDocument = response.getDocument(componentsField);
        final var versionsDocument = response.getDocument(versionsField);

        for (var versionEntry : versionsDocument.entrySet()) {
            final var namespace = ComponentNamespace.parse(versionEntry.getKey());
            final var version = versionEntry.getValue().asNumber().longValue();
            profile.initVersion(namespace, version);
        }

        for (var componentName : componentsDocument.keySet()) {
            if (!componentsDocument.isDocument(componentName)) {
                LOGGER.error("{} is not a document", componentName);
                skipped++;
                continue;
            }

            final var componentBson = componentsDocument.getDocument(componentName);
            final var componentCodec = componentRegistry.getCodec(ComponentNamespace.parse(componentName));
            if (componentCodec == null) {
                LOGGER.error("Could not find registered codec for component '{}' for player {} - is it registered via componentRegistry ?", componentName, uuid);
                skipped++;
                continue;
            }

            final var version = versionsDocument.containsKey(componentName) ? versionsDocument.getNumber(componentName).longValue() : 1L;

            try {
                final var component = componentCodec.decode(componentBson);
                profile.initComponent(ComponentNamespace.parse(componentName), component, version);
                loaded++;
            } catch (Exception e) {
                LOGGER.error("Failed to load component {} for player {}.", componentName, uuid);
                skipped++;
            }


        }

        LOGGER.info("Profile loaded for {} — {} component(s) loaded, {} skipped.", uuid, loaded, skipped);

        add(profile);
        if (tachyonCollector != null) tachyonCollector.updateProfilesCount(profiles.size());
        eventBus.post(new ProfileLoadedEvent(uuid, profile));

    }

    @Override
    public boolean profileIsLoaded(@NotNull final UUID uuid) {
        return this.profiles.containsKey(uuid);
    }

    @Override
    public void unloadProfile(@NotNull final UUID uuid, @NotNull final String reason) {
        TachyonProfile profile = remove(uuid);
        if (profile == null) return;
        profile.freeze();
        LOGGER.info("Profile unloaded and frozen for {}. Active profiles: {}", uuid, profiles.size());
        if (tachyonCollector != null) tachyonCollector.updateProfilesCount(profiles.size());
        eventBus.post(new ProfileUnloadedEvent(uuid, profile, reason));
    }

    @Override
    public void unloadProfile(@NotNull UUID uuid) {
        unloadProfile(uuid, "UNKNOWN");
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
