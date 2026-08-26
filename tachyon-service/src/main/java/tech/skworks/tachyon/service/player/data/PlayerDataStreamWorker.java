package tech.skworks.tachyon.service.player.data;

import com.mongodb.client.model.*;
import io.quarkus.mongodb.FindOptions;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.quarkus.mongodb.reactive.ReactiveMongoCollection;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.quarkus.redis.datasource.stream.ClaimedMessages;
import io.quarkus.redis.datasource.stream.ReactiveStreamCommands;
import io.quarkus.redis.datasource.stream.StreamMessage;
import io.quarkus.redis.datasource.stream.XReadGroupArgs;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.*;
import org.bson.conversions.Bson;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import tech.skworks.tachyon.service.infra.RedisKeys;
import tech.skworks.tachyon.service.player.PlayerConfig;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static tech.skworks.tachyon.service.player.data.PlayerDataGrpcService.toByteArray;

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
    PlayerConfig config;

    @ConfigProperty(name = "quarkus.mongodb.database", defaultValue = "tachyon")
    String dbName;

    private final ReactiveStreamCommands<String, String, byte[]> redisStream;
    private final ReactiveValueCommands<String, byte[]> redisBytes;
    private final ReactiveKeyCommands<String> redisKey;
    private int reclaimCycle = 0;

    private ReactiveMongoCollection<Document> playersCollection;
    private ReactiveMongoCollection<RawBsonDocument> rawPlayersCollection;

    public PlayerDataStreamWorker(ReactiveRedisDataSource redisDS) {
        this.redisStream = redisDS.stream(String.class, String.class, byte[].class);
        this.redisBytes = redisDS.value(byte[].class);
        this.redisKey = redisDS.key();
    }

    @PostConstruct
    void init() {
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

    private Uni<Void> processAndAck(List<StreamMessage<String, String, byte[]>> messages, String origin) {
        if (messages == null || messages.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        log.debugf("[PlayerStreamWorker] Processing %d %s message(s) from stream.", messages.size(), origin);

        List<WriteModel<Document>> bulkOperations = new ArrayList<>();
        List<String> messagesToAck = new ArrayList<>();
        List<String> updatedUuids = new ArrayList<>();
        int failed = 0;

        for (StreamMessage<String, String, byte[]> msg : messages) {
            byte[] saveProfilePayload = msg.payload().get("save_profile_payload");

            if (saveProfilePayload == null) {
                log.warnf("[PlayerStreamWorker] Message %s has no recognized payload key — poison, dropping (ACK).", msg.id());
                messagesToAck.add(msg.id());
                continue;
            }

            try {
                RawBsonDocument reqDoc = new RawBsonDocument(saveProfilePayload);
                String uuid = reqDoc.getString("uuid").getValue();

                Document updateOps = buildUpdateOperations(reqDoc);
                if (!updateOps.isEmpty()) {
                    bulkOperations.add(new UpdateOneModel<>(
                            Filters.eq("uuid", uuid),
                            updateOps,
                            new UpdateOptions().upsert(true)
                    ));
                    updatedUuids.add(uuid);
                }

                messagesToAck.add(msg.id());
            } catch (Exception e) {
                log.errorf(e, "[PlayerStreamWorker] Transient failure preparing message %s.", msg.id());
                failed++;
            }
        }

        Uni<Void> mongoUni = bulkOperations.isEmpty()
                ? Uni.createFrom().voidItem()
                : playersCollection.bulkWrite(bulkOperations, new BulkWriteOptions().ordered(false))
                .invoke(res -> log.infof("[PlayerStreamWorker] BulkWrite written %d operation(s) to MongoDB in 1 network call.", bulkOperations.size()))
                .replaceWithVoid()
                .onFailure().invoke(e -> log.error("[PlayerStreamWorker] Error during MongoDB bulkWrite batch execution.", e))
                .onFailure().recoverWithNull();

        final int finalFailed = failed;
        return mongoUni
                .chain(() -> updateCacheBatchAndUnlock(updatedUuids))
                .chain(() -> {
                    if (!messagesToAck.isEmpty()) {
                        return redisStream.xack(config.streamKey(), config.streamGroupName(), messagesToAck.toArray(new String[0])).replaceWithVoid();
                    }
                    return Uni.createFrom().voidItem();
                })
                .invoke(() -> {
                    if (finalFailed > 0)
                        log.warnf("[PlayerStreamWorker] %s cycle — %d ACKed, %d left pending for retry.", origin, messagesToAck.size(), finalFailed);
                    else
                        log.debugf("[PlayerStreamWorker] %s cycle — %d message(s) processed successfully.", origin, messagesToAck.size());
                });
    }

    private Document buildUpdateOperations(RawBsonDocument reqDoc) {
        Document updateOperations = new Document();
        Document setDocument = new Document();
        Document unsetDocument = new Document();

        if (reqDoc.containsKey("save") && reqDoc.isDocument("save")) {
            BsonDocument saveDocs = reqDoc.getDocument("save");
            for (String compName : saveDocs.keySet()) {
                setDocument.put("components." + compName, saveDocs.get(compName));
            }
        }

        if (reqDoc.containsKey("remove") && reqDoc.isArray("remove")) {
            BsonArray removeArray = reqDoc.getArray("remove");
            for (BsonValue val : removeArray) {
                if (val.isString()) {
                    unsetDocument.put("components." + val.asString().getValue(), "");
                }
            }
        }

        if (!setDocument.isEmpty()) {
            updateOperations.put("$set", setDocument);
        }
        if (!unsetDocument.isEmpty()) {
            updateOperations.put("$unset", unsetDocument);
        }

        return updateOperations;
    }

    private static final Bson PROJECTION = Projections.fields(Projections.include("uuid", "components"), Projections.excludeId());
    private static final FindOptions FIND_OPTIONS = new FindOptions().projection(PROJECTION);

    private Uni<Void> updateCacheBatchAndUnlock(List<String> uuids) {
        if (uuids == null || uuids.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        final var uniqueUuids = new HashSet<>(uuids);

        return rawPlayersCollection.find(Filters.in("uuid", uniqueUuids), FIND_OPTIONS).collect().asList()
                .chain(docs -> {
                    List<Uni<Void>> setexUnis = new ArrayList<>();
                    for (RawBsonDocument updatedDoc : docs) {
                        String uuid = updatedDoc.getString("uuid").getValue();
                        byte[] cacheBytes = toByteArray(updatedDoc);
                        setexUnis.add(redisBytes.setex(RedisKeys.cache(uuid), RedisKeys.CACHE_TTL_SECONDS, cacheBytes).invoke(() -> log.debugf("[PlayerStreamWorker] Cache updated for %s (%d bytes).", uuid, cacheBytes.length)));
                    }
                    return setexUnis.isEmpty() ? Uni.createFrom().voidItem() : Uni.combine().all().unis(setexUnis).discardItems();
                })
                .onFailure().invoke(e -> log.errorf(e, "[PlayerStreamWorker] Failed to batch update cache for %d player(s).", uniqueUuids.size()))
                .onFailure().recoverWithNull()
                .eventually(() -> {
                    List<String> dirtyKeys = uniqueUuids.stream().map(RedisKeys::dirty).toList();
                    if (!dirtyKeys.isEmpty()) {
                        return redisKey.del(dirtyKeys.toArray(new String[0]))
                                .invoke(deleted -> log.debugf("[PlayerStreamWorker] Released %d dirty key(s) in bulk Redis call.", dirtyKeys.size()))
                                .replaceWithVoid();
                    }
                    return Uni.createFrom().voidItem();
                });
    }
}
