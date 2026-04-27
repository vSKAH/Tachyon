package tech.skworks.tachyon.service.player.data;

import com.google.protobuf.Any;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.stream.StreamCommands;
import io.quarkus.redis.datasource.stream.StreamMessage;
import io.quarkus.redis.datasource.stream.XReadGroupArgs;
import io.quarkus.redis.datasource.value.ValueCommands;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import tech.skworks.tachyon.service.contracts.player.data.PullProfileResponse;
import tech.skworks.tachyon.service.contracts.player.data.PushProfileRequest;
import tech.skworks.tachyon.service.infra.DynamicProtobufRegistry;
import tech.skworks.tachyon.service.player.PlayerConfig;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
    DynamicProtobufRegistry protobufRegistry;
    @Inject
    PlayerConfig config;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String dbName;

    private final StreamCommands<String, String, byte[]> redisStream;
    private final ValueCommands<String, byte[]> redisBytes;
    private final KeyCommands<String> redisKey;

    private MongoCollection<Document> playersCollection;

    public PlayerDataStreamWorker(RedisDataSource redisDS) {
        this.redisStream = redisDS.stream(String.class, String.class, byte[].class);
        this.redisBytes = redisDS.value(byte[].class);
        this.redisKey = redisDS.key();
    }

    @PostConstruct
    void init() {
        this.playersCollection = mongo.getDatabase(dbName).getCollection(config.collection());
        this.log.infof("[PlayerStreamWorker] Initialized with consumer ID '%s' on stream '%s'.", config.consumerId(), config.streamKey());
    }

    @Scheduled(every = "1s", delay = 3L, delayUnit = TimeUnit.SECONDS, concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void processStream() {
        try {
            List<StreamMessage<String, String, byte[]>> messages = redisStream.xreadgroup(config.streamGroupName(), config.consumerId(), config.streamKey(), ">", new XReadGroupArgs().count(50));

            if (messages == null || messages.isEmpty()) return;

            log.debugf("[PlayerStreamWorker] Picked up %d message(s) from stream.", messages.size());

            int failed = 0;
            List<String> messagesToAck = new ArrayList<>();

            for (StreamMessage<String, String, byte[]> msg : messages) {
                if (processSingleMessage(msg)) messagesToAck.add(msg.id());
                else failed++;
            }

            if (!messagesToAck.isEmpty()) {
                redisStream.xack(config.streamKey(), config.streamGroupName(), messagesToAck.toArray(new String[0]));
            }

            if (failed > 0)
                log.warnf("[PlayerStreamWorker] Cycle complete — %d processed, %d failed (will not be ACKed).", messagesToAck.size(), failed);
            else
                log.debugf("[PlayerStreamWorker] Cycle complete — %d message(s) processed successfully.", messagesToAck.size());

        } catch (Exception e) {
            log.error("[PlayerStreamWorker] Fatal error in stream processing loop.", e);
        }
    }

    private boolean processSingleMessage(StreamMessage<String, String, byte[]> msg) {
        String uuid = null;
        try {
            byte[] saveProfilePayload = msg.payload().get("save_profile_payload");

            if (saveProfilePayload == null) {
                log.warnf("[PlayerStreamWorker] Message %s has no recognized payload key — discarding.", msg.id());
                return false;
            }

            PushProfileRequest req = PushProfileRequest.parseFrom(saveProfilePayload);
            uuid = req.getUuid();
            handleSaveProfilePayload(req, uuid);
            return true;
        } catch (Exception e) {
            log.errorf(e, "[PlayerStreamWorker] Failed to process message %s (uuid=%s).", msg.id(), uuid);
            if (uuid != null) {
                redisKey.del("player:dirty:" + uuid);
                log.warnf("[PlayerStreamWorker] Released dirty key for %s after processing error.", uuid);
            }
            return false;
        }
    }

    private void handleSaveProfilePayload(PushProfileRequest request, String uuid) throws InvalidProtocolBufferException {
        log.debugf("[PlayerStreamWorker] Processing SaveProfile for %s (%d component(s) to saves, %d component(s)) to remove.", uuid,
                request.getComponentsToSaveCount(), request.getComponentsToRemoveCount());

        Document updateOperations = new Document();
        Document setDocument = new Document();
        Document unsetDocument = new Document();

        for (Any any : request.getComponentsToSaveList()) {
            String typeUrl = any.getTypeUrl();

            Document docToInsert = new Document(Document.parse(protobufRegistry.getPrinter().print(any)));
            docToInsert.put("@type", DynamicProtobufRegistry.stripTypeURL(typeUrl));
            setDocument.put("components." + toCleanKey(typeUrl), docToInsert);
        }

        for (String urlToRemove : request.getComponentsToRemoveList()) {
            unsetDocument.put("components." + toCleanKey(DynamicProtobufRegistry.stripTypeURL(urlToRemove)), "");
        }

        if (!setDocument.isEmpty()) {
            updateOperations.put("$set", setDocument);
        }
        if (!unsetDocument.isEmpty()) {
            updateOperations.put("$unset", unsetDocument);
        }

        if (!updateOperations.isEmpty()) {
            // takeSnapshotIfAllowed(uuid, "AUTO_PROFILE_SAVE");

            playersCollection.updateOne(Filters.eq("uuid", uuid), updateOperations, new UpdateOptions().upsert(true)
            );
            log.infof("[PlayerStreamWorker] SaveProfile written to MongoDB for %s.", uuid);
        } else {
            log.debugf("[PlayerStreamWorker] Empty batch for %s, MongoDB update skipped.", uuid);
        }

        updateCacheAndUnlock(uuid);
    }

    private void takeSnapshotIfAllowed(String uuid, String reason) {
        String throttleKey = "snapshot:throttle:" + uuid;
        //  boolean allowed = redisString.setAndChanged(throttleKey, "1", new SetArgs().nx().ex(snapshotThrottleSeconds));

        // if (!allowed) {
        //     log.debugf("[PlayerStreamWorker] Snapshot throttled for %s (within %ds window, reason: %s).", uuid, snapshotThrottleSeconds, reason);
        //    return;
        //  }

        Document existing = playersCollection.find(Filters.eq("uuid", uuid)).first();
        if (existing != null) {
            Document toSnapshot = new Document(existing);
            toSnapshot.remove("_id");

            byte[] data = toSnapshot.toJson().getBytes(StandardCharsets.UTF_8);
            //   snapshotsCollection.insertOne(new Document("uuid", uuid).append("timestamp", System.currentTimeMillis()).append("reason", reason).append("data", new Binary(data)));
            log.infof("[PlayerStreamWorker] Snapshot created for %s (reason: %s, size: %d bytes).", uuid, reason, data.length);
        } else {
            log.debugf("[PlayerStreamWorker] No existing document to snapshot for %s.", uuid);
        }
    }

    private void updateCacheAndUnlock(String uuid) {
        try {
            Document updatedDoc = playersCollection.find(Filters.eq("uuid", uuid)).first();
            if (updatedDoc == null) {
                log.warnf("[PlayerStreamWorker] updateCacheAndUnlock: no document found for %s — cache not updated.", uuid);
                return;
            }

            PullProfileResponse.Builder response = PullProfileResponse.newBuilder().setUuid(uuid);
            int cacheHits = 0;
            int cacheMisses = 0;

            if (updatedDoc.containsKey("components")) {
                Document components = updatedDoc.get("components", Document.class);
                for (String dbKey : components.keySet()) {
                    Document compDoc = components.get(dbKey, Document.class);
                    String shortType = compDoc.getString("@type");

                    if (shortType == null) {
                        log.warnf("[PlayerStreamWorker] Component '%s' missing '@type' field for player %s — skipped.", dbKey, uuid);
                        cacheMisses++;
                        continue;
                    }

                    Document compForJson = new Document(compDoc);
                    compForJson.remove("@type");

                    Any any = buildAnyFromJson(shortType, compForJson.toJson());
                    if (any != null) {
                        response.addComponents(any);
                        cacheHits++;
                    } else {
                        log.warnf("[PlayerStreamWorker] Unknown type '%s' for player %s — is the .desc loaded?", shortType, uuid);
                        cacheMisses++;
                    }
                }
            }
            byte[] cacheBytes = response.build().toByteArray();
            redisBytes.setex("player:cache:" + uuid, 60, cacheBytes);
            log.infof("[PlayerStreamWorker] Cache updated for %s — %d component(s) cached, %d skipped (%d bytes).", uuid, cacheHits, cacheMisses, cacheBytes.length);
        } catch (Exception e) {
            log.errorf(e, "[PlayerStreamWorker] Failed to update cache for %s.", uuid);
        } finally {
            redisKey.del("player:dirty:" + uuid);
            log.infof("[PlayerStreamWorker] Dirty key released for %s.", uuid);
        }
    }

    private Any buildAnyFromJson(String protoFullName, String json) {
        try {
            Descriptors.Descriptor descriptor = protobufRegistry.findDescriptor(protoFullName);
            if (descriptor == null) return null;

            DynamicMessage.Builder builder = DynamicMessage.newBuilder(descriptor);
            JsonFormat.parser().usingTypeRegistry(protobufRegistry.getTypeRegistry()).ignoringUnknownFields().merge(json, builder);

            return Any.pack(builder.build());
        } catch (Exception e) {
            log.errorf(e, "[PlayerStreamWorker] buildAnyFromJson failed for type '%s'.", protoFullName);
            return null;
        }
    }

    public static String toCleanKey(String typeUrl) {
        return typeUrl.substring(typeUrl.lastIndexOf('.') + 1);
    }
}
