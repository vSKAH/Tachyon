package tech.skworks.tachyon.service.player.data;

import com.mongodb.client.model.*;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.quarkus.mongodb.reactive.ReactiveMongoCollection;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.stream.ClaimedMessages;
import io.quarkus.redis.datasource.stream.ReactiveStreamCommands;
import io.quarkus.redis.datasource.stream.StreamMessage;
import io.quarkus.redis.datasource.stream.XReadGroupArgs;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.common.constraint.NotNull;
import io.smallrye.common.constraint.Nullable;
import io.smallrye.mutiny.Uni;
import io.vertx.redis.client.Command;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.*;
import org.bson.conversions.Bson;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import tech.skworks.tachyon.common.BsonUtil;
import tech.skworks.tachyon.common.contract.PlayerDataContract;
import tech.skworks.tachyon.service.player.PlayerConfig;
import tech.skworks.tachyon.service.player.RedisKeys;
import tech.skworks.tachyon.service.player.RedisScripts;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Project Tachyon
 * Class PlayerDataStreamWorker
 * Fully Reactive Player Data Stream Processor
 *
 * @author  Jimmy (vSKAH) - 06/04/2026
 * @version 2.0
 * @since 1.0.0-SNAPSHOT
 */
@ApplicationScoped
class PlayerDataStreamWorker {

    @Inject
    Logger log;

    @Inject
    ReactiveMongoClient mongo;

    @Inject
    ReactiveRedisDataSource redis;

    @Inject
    PlayerConfig config;

    @ConfigProperty(name = "quarkus.mongodb.database", defaultValue = "tachyon")
    String dbName;

    private ReactiveStreamCommands<String, String, byte[]> redisStream;
    private ReactiveValueCommands<String, byte[]> redisBytes;
    private int reclaimCycle = 0;

    private ReactiveMongoCollection<Document> playersCollection;
    private ReactiveMongoCollection<RawBsonDocument> rawPlayersCollection;


    @PostConstruct
    void init() {

        this.redisStream = redis.stream(String.class, String.class, byte[].class);
        this.redisBytes = redis.value(byte[].class);

        this.playersCollection = mongo.getDatabase(dbName).getCollection(config.collection());
        this.rawPlayersCollection = mongo.getDatabase(dbName).getCollection(config.collection(), RawBsonDocument.class);
        this.log.infof("[PlayerStreamWorker] Initialized with consumer ID '%s' on stream '%s'.", config.consumerId(), config.streamKey());
    }

    @Scheduled(every = "1s", delay = 3L, delayUnit = TimeUnit.SECONDS, concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    Uni<Void> processStream() {
        return readFreshMessages()
                .chain(() -> (++reclaimCycle >= 20) ? reclaimAbandonedMessages() : Uni.createFrom().voidItem())
                .onFailure().invoke(e -> log.error("[PlayerStreamWorker] Error in stream processing loop.", e))
                .onFailure().recoverWithNull();
    }

    private Uni<Void> readFreshMessages() {
        return redisStream.xreadgroup(config.streamGroupName(), config.consumerId(), config.streamKey(), ">", new XReadGroupArgs().count(200))
                .chain(messages -> processAndAck(messages, "new"));
    }

    private Uni<Void> reclaimAbandonedMessages() {
        reclaimCycle = 0;
        return redisStream.xautoclaim(config.streamKey(), config.streamGroupName(), config.consumerId(), Duration.ofSeconds(30), "0", 100)
                .map(ClaimedMessages::getMessages)
                .chain(messages -> processAndAck(messages, "reclaimed"));
    }

    private Uni<Void> processAndAck(final List<StreamMessage<String, String, byte[]>> messages, @NotNull final String origin) {
        if (messages == null || messages.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        log.debugf("[PlayerStreamWorker] Processing %d %s message(s) from stream.", messages.size(), origin);

        final var poisonMessagesToAck = new ArrayList<String>();
        final var validMessagesToAck = new ArrayList<String>();
        final var allValidUuids = new HashSet<String>();
        final var updatesByUuid = new LinkedHashMap<String, PlayerUpdate>();

        for (final var message : messages) {
            final var payload = message.payload();
            final byte[] saveProfilePayload = (payload != null) ? payload.get("save_profile_payload") : null;

            if (saveProfilePayload == null || saveProfilePayload.length == 0) {
                log.warnf("[PlayerStreamWorker] Message %s has no recognized payload key — poison, dropping (ACK).", message.id());
                poisonMessagesToAck.add(message.id());
                continue;
            }

            final RawBsonDocument requestDocument;
            try {
                requestDocument = new RawBsonDocument(saveProfilePayload);
            } catch (Exception e) {
                log.warnf("[PlayerStreamWorker] Message %s has corrupted BSON payload (%s) — poison, dropping (ACK).", message.id(), e.getMessage());
                poisonMessagesToAck.add(message.id());
                continue;
            }

            if (!requestDocument.containsKey(PlayerDataContract.FIELD_UUID) || !requestDocument.isString(PlayerDataContract.FIELD_UUID)) {
                log.warnf("[PlayerStreamWorker] Message %s has missing or non-string 'uuid' — poison, dropping (ACK).", message.id());
                poisonMessagesToAck.add(message.id());
                continue;
            }

            final var uuid = requestDocument.getString(PlayerDataContract.FIELD_UUID).getValue();
            if (uuid.isBlank()) {
                log.warnf("[PlayerStreamWorker] Message %s has blank 'uuid' — poison, dropping (ACK).", message.id());
                poisonMessagesToAck.add(message.id());
                continue;
            }

            try {
                final var playerUpdate = updatesByUuid.computeIfAbsent(uuid, k -> new PlayerUpdate());
                playerUpdate.apply(requestDocument);
                allValidUuids.add(uuid);
                validMessagesToAck.add(message.id());
            } catch (Exception e) {
                log.warnf(e, "[PlayerStreamWorker] Message %s has invalid structure — poison, dropping (ACK).", message.id());
                poisonMessagesToAck.add(message.id());
            }
        }

        final Uni<Void> ackPoisonUni = poisonMessagesToAck.isEmpty()
                ? Uni.createFrom().voidItem()
                : redisStream.xack(config.streamKey(), config.streamGroupName(), poisonMessagesToAck.toArray(new String[0]))
                .invoke(count -> log.warnf("[PlayerStreamWorker] Dropped %d poison message(s) (ACKed).", count))
                .replaceWithVoid()
                .onFailure().invoke(e -> log.error("[PlayerStreamWorker] Failed to ACK poison messages.", e))
                .onFailure().recoverWithNull();

        if (validMessagesToAck.isEmpty()) {
            return ackPoisonUni;
        }

        final var bulkOperations = new ArrayList<WriteModel<Document>>();
        for (final var entry : updatesByUuid.entrySet()) {
            final var uuid = entry.getKey();
            final var playerUpdate = entry.getValue();
            if (playerUpdate != null) {
                bulkOperations.addAll(playerUpdate.toModels(uuid));
            }
        }

        final Uni<Void> mongoUni = bulkOperations.isEmpty()
                ? Uni.createFrom().voidItem()
                : playersCollection.bulkWrite(bulkOperations, new BulkWriteOptions().ordered(false))
                .invoke(res -> log.infof("[PlayerStreamWorker] BulkWrite written %d operation(s) to MongoDB in 1 network call.", bulkOperations.size()))
                .replaceWithVoid()
                .onFailure().invoke(e -> log.error("[PlayerStreamWorker] Error during MongoDB bulkWrite batch execution.", e));

        final Uni<Void> processValidUni = mongoUni
                .chain(() -> updateCacheBatchAndUnlock(allValidUuids))
                .chain(() -> redisStream.xack(config.streamKey(), config.streamGroupName(), validMessagesToAck.toArray(new String[0])).replaceWithVoid())
                .invoke(() -> log.debugf("[PlayerStreamWorker] %s cycle — %d message(s) processed successfully.", origin, validMessagesToAck.size()));

        return ackPoisonUni.chain(() -> processValidUni);
    }

    private static final class PlayerUpdate {
        private final Map<String, BsonValue> componentSaves = new HashMap<>();
        private final Map<String, Long> componentVersions = new HashMap<>();
        private final Set<String> componentRemoves = new HashSet<>();

        public void apply(@NotNull final RawBsonDocument requestDocument) {
            Objects.requireNonNull(requestDocument, "requestDocument must not be null");

            final var incomingVersions = extractVersions(requestDocument);
            final var saveField = PlayerDataContract.FIELD_SAVE;

            if (requestDocument.containsKey(saveField) && requestDocument.isDocument(saveField)) {
                final var savesDocument = requestDocument.getDocument(saveField);
                for (final var componentName : savesDocument.keySet()) {
                    final var incomingVersion = incomingVersions.get(componentName);
                    final var currentVersion = componentVersions.get(componentName);

                    if (isStale(incomingVersion, currentVersion)) continue;

                    componentSaves.put(componentName, savesDocument.get(componentName));

                    componentRemoves.remove(componentName);
                    if (incomingVersion != null) {
                        componentVersions.put(componentName, incomingVersion);
                    }
                }
            }

            final var removeField = PlayerDataContract.FIELD_REMOVE;
            if (requestDocument.containsKey(removeField) && requestDocument.isArray(removeField)) {
                final var removeArray = requestDocument.getArray(removeField);

                for (final var arrayElement : removeArray) {
                    if (!arrayElement.isString()) continue;
                    final var componentName = arrayElement.asString().getValue();
                    final var incomingVersion = incomingVersions.get(componentName);
                    final var currentVersion = componentVersions.get(componentName);

                    if (isStale(incomingVersion, currentVersion)) continue;

                    componentRemoves.add(componentName);
                    componentSaves.remove(componentName);
                    if (incomingVersion != null) {
                        componentVersions.put(componentName, incomingVersion);
                    }

                }

            }
        }

        public static boolean isStale(@Nullable final Long incomingVersion, @Nullable final Long currentVersion) {
            return incomingVersion != null && currentVersion != null && incomingVersion <= currentVersion;
        }

        private static Bson buildVersionFilter(@NotNull final String uuid, @NotNull final String componentName, @Nullable final Long version) {
            if (version == null) {
                return Filters.eq(PlayerDataContract.FIELD_UUID, uuid);
            }
            final var specificVersionField = PlayerDataContract.FIELD_VERSIONS + '.' + componentName;
            return Filters.and(
                    Filters.eq(PlayerDataContract.FIELD_UUID, uuid),
                    Filters.or(Filters.exists(specificVersionField, false), Filters.lte(specificVersionField, version))
            );
        }

        private static Map<String, Long> extractVersions(@NotNull final RawBsonDocument doc) {
            final var versionsField = PlayerDataContract.FIELD_VERSIONS;
            if (!doc.containsKey(versionsField) || !doc.isDocument(versionsField)) {
                return Map.of();
            }
            final var versionsDoc = doc.getDocument(versionsField);
            final var map = new HashMap<String, Long>(versionsDoc.size());
            for (final var key : versionsDoc.keySet()) {
                map.put(key, versionsDoc.getNumber(key).longValue());
            }
            return map;
        }

        public List<WriteModel<Document>> toModels(final @NotNull String uuid) {

            if (componentSaves.isEmpty() && componentRemoves.isEmpty()) {
                return List.of();
            }

            final var models = new ArrayList<WriteModel<Document>>((componentSaves.size() + componentRemoves.size()) * 2);

            models.add(new UpdateOneModel<>(
                    Filters.eq(PlayerDataContract.FIELD_UUID, uuid),
                    Updates.setOnInsert(PlayerDataContract.FIELD_UUID, uuid),
                    new UpdateOptions().upsert(true)
            ));

            for (final var saveEntry : componentSaves.entrySet()) {
                final var componentName = saveEntry.getKey();
                final var componentData = saveEntry.getValue();
                final var componentVersion = componentVersions.get(componentName);

                final var filter = buildVersionFilter(uuid, componentName, componentVersion);
                Bson update;

                final var specificComponentField = PlayerDataContract.FIELD_COMPONENTS + '.' + componentName;
                final var specificVersionField = PlayerDataContract.FIELD_VERSIONS + '.' + componentName;

                if (componentVersion != null) {
                    update = Updates.combine(Updates.set(specificComponentField, componentData), Updates.set(specificVersionField, componentVersion));
                } else {
                    update = Updates.set(specificComponentField, componentData);
                }
                models.add(new UpdateOneModel<>(filter, update, new UpdateOptions().upsert(false)));
            }

            for (final var componentNameRemove : componentRemoves) {

                final var componentVersion = componentVersions.get(componentNameRemove);
                final var filter = buildVersionFilter(uuid, componentNameRemove, componentVersion);

                Bson update;
                final var specificComponentField = PlayerDataContract.FIELD_COMPONENTS + '.' + componentNameRemove;
                final var specificVersionField = PlayerDataContract.FIELD_VERSIONS + '.' + componentNameRemove;

                if (componentVersion != null)
                    update = Updates.combine(Updates.unset(specificComponentField), Updates.set(specificVersionField, componentVersion));
                else
                    update = Updates.unset(specificComponentField);


                models.add(new UpdateOneModel<>(filter, update, new UpdateOptions().upsert(false)));
            }

            return models;
        }


    }

    private Uni<Void> updateCacheBatchAndUnlock(@NotNull final Collection<String> uuids) {
        Objects.requireNonNull(uuids, "uuids cannot be null.");

        if (uuids.isEmpty()) return Uni.createFrom().voidItem();

        final var uniqueUuids = (uuids instanceof Set<String> set) ? set : new HashSet<>(uuids);

        return rawPlayersCollection.find(Filters.in(PlayerDataContract.FIELD_UUID, uniqueUuids), PlayerDataGrpcService.PULL_PROFILE_FIND_OPTIONS).collect().asList()
                .chain(docs -> {
                    final var setExpirationsUni = new ArrayList<Uni<Void>>();
                    for (final RawBsonDocument updatedDocument : docs) {
                        final var uuid = updatedDocument.getString(PlayerDataContract.FIELD_UUID).getValue();
                        final var bytesToCache = BsonUtil.toByteArray(updatedDocument);
                        setExpirationsUni.add(redisBytes.setex(RedisKeys.cache(uuid), RedisKeys.CACHE_TTL_SECONDS, bytesToCache).invoke(() -> log.debugf("[PlayerStreamWorker] Cache updated for %s (%d bytes).", uuid, bytesToCache.length)));
                    }
                    return setExpirationsUni.isEmpty() ? Uni.createFrom().voidItem() : Uni.combine().all().unis(setExpirationsUni).discardItems();
                })
                .onFailure().invoke(e -> log.errorf(e, "[PlayerStreamWorker] Failed to batch update cache for %d player(s).", uniqueUuids.size()))
                .onFailure().recoverWithNull()
                .eventually(() -> {
                    final var dirtyKeys = uniqueUuids.stream().map(RedisKeys::dirty).toList();
                    if (dirtyKeys.isEmpty()) {
                        return Uni.createFrom().voidItem();
                    }

                    final var args = new ArrayList<String>(dirtyKeys.size() + 3);
                    args.add(RedisScripts.DIRTY_BATCH_DECREMENT_LUA);
                    args.add(String.valueOf(dirtyKeys.size()));
                    args.addAll(dirtyKeys);
                    args.add(RedisKeys.DIRTY_TTL_SECONDS_STRING);


                    return redis.execute(Command.EVAL, args.toArray(String[]::new))
                            .invoke(deleted -> log.debugf("[PlayerStreamWorker] Released %d dirty key(s) in bulk Redis call.", deleted))
                            .replaceWithVoid();
                });
    }
}
