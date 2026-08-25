package tech.skworks.tachyon.service.player.data;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.*;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.stream.ClaimedMessages;
import io.quarkus.redis.datasource.stream.StreamCommands;
import io.quarkus.redis.datasource.stream.StreamMessage;
import io.quarkus.redis.datasource.stream.XReadGroupArgs;
import io.quarkus.redis.datasource.value.ValueCommands;
import io.quarkus.scheduler.Scheduled;
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
 * Class PlayerStreamWorker
 *
 * @author  Jimmy (vSKAH) - 06/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
@ApplicationScoped
class PlayerDataStreamWorker {

    @Inject
    Logger log;
    @Inject
    MongoClient mongo;
    @Inject
    PlayerConfig config;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String dbName;

    private final StreamCommands<String, String, byte[]> redisStream;
    private final ValueCommands<String, byte[]> redisBytes;
    private final KeyCommands<String> redisKey;

    private MongoCollection<Document> playersCollection;
    private MongoCollection<RawBsonDocument> rawPlayersCollection;

    public PlayerDataStreamWorker(RedisDataSource redisDS) {
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
    void processStream() {
        try {

            ClaimedMessages<String, String, byte[]> claimed = redisStream.xautoclaim(config.streamKey(), config.streamGroupName(), config.consumerId(), Duration.ofSeconds(30), "0", 100);
            processAndAck(claimed.getMessages(), "reclaimed");

            List<StreamMessage<String, String, byte[]>> fresh = redisStream.xreadgroup(config.streamGroupName(), config.consumerId(), config.streamKey(), ">", new XReadGroupArgs().count(200));
            if (fresh != null && !fresh.isEmpty()) {
                processAndAck(fresh, "new");
            }


        } catch (Exception e) {
            log.error("[PlayerStreamWorker] Error in stream processing loop.", e);
        }
    }

    private void processAndAck(List<StreamMessage<String, String, byte[]>> messages, String origin) {
        if (messages == null || messages.isEmpty()) return;

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

        if (!bulkOperations.isEmpty()) {
            try {
                playersCollection.bulkWrite(bulkOperations, new BulkWriteOptions().ordered(false));
                log.infof("[PlayerStreamWorker] BulkWrite written %d operation(s) to MongoDB in 1 network call.", bulkOperations.size());
            } catch (Exception e) {
                log.error("[PlayerStreamWorker] Error during MongoDB bulkWrite batch execution.", e);
                return;
            }
        }

        if (!updatedUuids.isEmpty()) {
            updateCacheBatchAndUnlock(updatedUuids);
        }

        if (!messagesToAck.isEmpty()) {
            redisStream.xack(config.streamKey(), config.streamGroupName(), messagesToAck.toArray(new String[0]));
        }

        if (failed > 0)
            log.warnf("[PlayerStreamWorker] %s cycle — %d ACKed, %d left pending for retry.", origin, messagesToAck.size(), failed);
        else
            log.debugf("[PlayerStreamWorker] %s cycle — %d message(s) processed successfully.", origin, messagesToAck.size());
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

    private void updateCacheBatchAndUnlock(List<String> uuids) {
        final var uniqueUuids = new HashSet<>(uuids);

        try {
            List<RawBsonDocument> docs = rawPlayersCollection.find(Filters.in("uuid", uniqueUuids)).projection(PROJECTION).into(new ArrayList<>());

            for (RawBsonDocument updatedDoc : docs) {
                String uuid = updatedDoc.getString("uuid").getValue();
                byte[] cacheBytes = toByteArray(updatedDoc);
                redisBytes.setex(RedisKeys.cache(uuid), RedisKeys.CACHE_TTL_SECONDS, cacheBytes);
                log.debugf("[PlayerStreamWorker] Cache updated for %s (%d bytes).", uuid, cacheBytes.length);
            }
        } catch (Exception e) {
            log.errorf(e, "[PlayerStreamWorker] Failed to batch update cache for %d player(s).", uniqueUuids.size());
        } finally {
            List<String> dirtyKeys = uniqueUuids.stream().map(RedisKeys::dirty).toList();
            if (!dirtyKeys.isEmpty()) {
                redisKey.del(dirtyKeys.toArray(new String[0]));
                log.debugf("[PlayerStreamWorker] Released %d dirty key(s) in bulk Redis call.", dirtyKeys.size());
            }
        }
    }
}
