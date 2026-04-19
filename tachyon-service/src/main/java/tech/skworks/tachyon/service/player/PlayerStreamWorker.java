package tech.skworks.tachyon.service.player;

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
import tech.skworks.tachyon.service.contracts.player.DeleteComponentRequest;
import tech.skworks.tachyon.service.contracts.player.GetPlayerResponse;
import tech.skworks.tachyon.service.contracts.player.SaveComponentRequest;
import tech.skworks.tachyon.service.contracts.player.SaveProfileRequest;
import tech.skworks.tachyon.service.infra.DynamicProtobufRegistry;

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
class PlayerStreamWorker {

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

    public PlayerStreamWorker(RedisDataSource redisDS) {
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
            byte[] saveComponentPayload = msg.payload().get("save_component_payload");
            byte[] deleteComponentPayload = msg.payload().get("delete_component_payload");

            if (saveComponentPayload == null && saveProfilePayload == null && deleteComponentPayload == null) {
                log.warnf("[PlayerStreamWorker] Message %s has no recognized payload key — discarding.", msg.id());
                return false;
            }

            if (saveProfilePayload != null) {
                SaveProfileRequest req = SaveProfileRequest.parseFrom(saveProfilePayload);
                uuid = req.getUuid();
                handleSaveProfilePayload(req, uuid);
            }
            else if (saveComponentPayload != null) {
                SaveComponentRequest req = SaveComponentRequest.parseFrom(saveComponentPayload);
                uuid = req.getUuid();
                handleSaveComponentPayload(req, uuid);
            }
            else if (deleteComponentPayload != null) {
                DeleteComponentRequest req = DeleteComponentRequest.parseFrom(deleteComponentPayload);
                uuid = req.getUuid();
                handleDeleteComponentPayload(req, uuid);
            }
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

    private void handleSaveProfilePayload(SaveProfileRequest request, String uuid) throws InvalidProtocolBufferException {
        log.debugf("[PlayerStreamWorker] Processing SaveProfile for %s (%d component(s)).", uuid, request.getComponentsCount());

        Document setDocument = new Document();

        for (Any any : request.getComponentsList()) {
            String fullTypeUrl = any.getTypeUrl();
            String cleanKey = toCleanKey(fullTypeUrl);
            String shortType = toShortType(fullTypeUrl);

            Document docToInsert = new Document(Document.parse(protobufRegistry.getPrinter().print(any)));
            docToInsert.put("@type", shortType);
            setDocument.put("components." + cleanKey, docToInsert);
        }

        // takeSnapshotIfAllowed(uuid, "AUTO_PROFILE_SAVE");
        playersCollection.updateOne(Filters.eq("uuid", uuid), new Document("$set", setDocument), new UpdateOptions().upsert(true));

        log.infof("[PlayerStreamWorker] SaveProfile written to MongoDB for %s.", uuid);
        updateCacheAndUnlock(uuid);
    }

    private void handleSaveComponentPayload(SaveComponentRequest request, String uuid) throws InvalidProtocolBufferException {
        String fullTypeUrl = request.getComponent().getTypeUrl();
        String cleanKey = toCleanKey(fullTypeUrl);
        String shortType = toShortType(fullTypeUrl);

        log.debugf("[PlayerStreamWorker] Processing SaveComponent '%s' for %s.", shortType, uuid);

        Document docToInsert = new Document(Document.parse(protobufRegistry.getPrinter().print(request.getComponent())));
        docToInsert.put("@type", shortType);

        Document existingPlayer = playersCollection.find(Filters.eq("uuid", uuid)).first();
        boolean hasChanged = true;

        if (existingPlayer != null && existingPlayer.containsKey("components")) {
            Document existingComp = existingPlayer.get("components", Document.class).get(cleanKey, Document.class);
            if (existingComp != null && existingComp.equals(docToInsert)) {
                hasChanged = false;
                log.debugf("[PlayerStreamWorker] Component '%s' for %s is unchanged — skipping MongoDB write.", shortType, uuid);
            }
        }

        if (hasChanged) {
            //  takeSnapshotIfAllowed(uuid, "AUTO_UPDATE_" + cleanKey);
            playersCollection.updateOne(Filters.eq("uuid", uuid), new Document("$set", new Document("components." + cleanKey, docToInsert)), new UpdateOptions().upsert(true));
            log.infof("[PlayerStreamWorker] SaveComponent '%s' written to MongoDB for %s.", shortType, uuid);
        }

        updateCacheAndUnlock(uuid);
    }

    private void handleDeleteComponentPayload(DeleteComponentRequest request, String uuid) {
        String fullTypeUrl = request.getComponentUrl();
        String cleanKey = toCleanKey(fullTypeUrl);
        String shortType = toShortType(fullTypeUrl);

        log.debugf("[PlayerStreamWorker] Processing DeleteComponent '%s' for %s.", shortType, uuid);

        //takeSnapshotIfAllowed(uuid, "AUTO_DELETE_" + cleanKey);
        playersCollection.updateOne(Filters.eq("uuid", uuid), new Document("$unset", new Document("components." + cleanKey, "")));

        log.infof("[PlayerStreamWorker] DeleteComponent '%s' written to MongoDB for %s.", shortType, uuid);
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

            GetPlayerResponse.Builder response = GetPlayerResponse.newBuilder().setUuid(uuid);
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

    /** "type.googleapis.com/tech.skworks.cookies.CookieComponent" → "CookieComponent" */
    private String toCleanKey(String typeUrl) {
        return typeUrl.substring(typeUrl.lastIndexOf('.') + 1);
    }

    /** "type.googleapis.com/tech.skworks.cookies.CookieComponent" → "tech.skworks.cookies.CookieComponent" */
    private String toShortType(String typeUrl) {
        return typeUrl.replace("type.googleapis.com/", "");
    }
}
