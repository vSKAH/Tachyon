package tech.skworks.tachyon.service.audit;

import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.quarkus.mongodb.reactive.ReactiveMongoCollection;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.stream.ClaimedMessages;
import io.quarkus.redis.datasource.stream.ReactiveStreamCommands;
import io.quarkus.redis.datasource.stream.StreamMessage;
import io.quarkus.redis.datasource.stream.XReadGroupArgs;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.RawBsonDocument;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Worker that consumes audit logs from Redis Stream and batches them into MongoDB TimeSeries reactively.
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @author Jimmy (vSKAH) - 25/08/2026
 * @version 2.0
 * @since 1.0.0-SNAPSHOT
 */
@ApplicationScoped
class AuditStreamWorker {

    @Inject
    Logger log;

    @Inject
    ReactiveMongoClient mongo;

    @Inject
    AuditConfig config;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String dbName;

    private final ReactiveStreamCommands<String, String, byte[]> redisStream;
    private ReactiveMongoCollection<Document> auditCollection;
    private int reclaimCycle = 0;

    public AuditStreamWorker(ReactiveRedisDataSource redisDS) {
        this.redisStream = redisDS.stream(String.class, String.class, byte[].class);
    }

    @PostConstruct
    void init() {
        this.auditCollection = mongo.getDatabase(dbName).getCollection(config.collection());
        log.infof("[AuditStreamWorker] Initialized with consumer ID '%s' on stream '%s'.", config.consumerId(), config.streamKey());
    }

    @Scheduled(every = "1s", delay = 3L, delayUnit = TimeUnit.SECONDS, concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    Uni<Void> processAuditStream() {
        return readFreshMessages()
                .chain(() -> (++reclaimCycle >= 20) ? reclaimAbandonedMessages() : Uni.createFrom().voidItem())
                .onFailure().invoke(e -> log.error("[AuditStreamWorker] Error in audit stream loop", e))
                .onFailure().recoverWithNull();
    }

    private Uni<Void> readFreshMessages() {
        return redisStream.xreadgroup(config.streamGroupName(), config.consumerId(), config.streamKey(), ">", new XReadGroupArgs().count(100))
                .chain(this::processAuditBatch);
    }

    private Uni<Void> reclaimAbandonedMessages() {
        reclaimCycle = 0;
        return redisStream.xautoclaim(config.streamKey(), config.streamGroupName(), config.consumerId(), Duration.ofSeconds(30), "0", 100)
                .map(ClaimedMessages::getMessages)
                .chain(this::processAuditBatch);
    }

    private Uni<Void> processAuditBatch(List<StreamMessage<String, String, byte[]>> messages) {
        if (messages == null || messages.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        List<Document> docsToInsert = new ArrayList<>();
        List<String> parsedIds = new ArrayList<>();
        List<String> poisonIds = new ArrayList<>();

        for (StreamMessage<String, String, byte[]> msg : messages) {
            try {
                byte[] batchPayload = msg.payload().get("payload");
                byte[] directPayload = msg.payload().get("direct_payload");

                if (batchPayload != null) {
                    RawBsonDocument raw = new RawBsonDocument(batchPayload);
                    if (raw.containsKey("values") && raw.isArray("values")) {
                        for (BsonValue val : raw.getArray("values")) {
                            if (val.isDocument()) {
                                docsToInsert.add(convertBsonToMongoDoc(val.asDocument()));
                            }
                        }
                    }
                } else if (directPayload != null) {
                    RawBsonDocument raw = new RawBsonDocument(directPayload);
                    docsToInsert.add(convertBsonToMongoDoc(raw));
                }

                parsedIds.add(msg.id());
            } catch (Exception e) {
                log.errorf(e, "Failed to parse audit message %s — poison, dropping (ACK).", msg.id());
                poisonIds.add(msg.id());
            }
        }

        List<String> idsToAck = new ArrayList<>(poisonIds);

        Uni<Void> insertUni = docsToInsert.isEmpty()
                ? Uni.createFrom().voidItem()
                : auditCollection.insertMany(docsToInsert)
                .invoke(() -> idsToAck.addAll(parsedIds))
                .replaceWithVoid()
                .onFailure().invoke(e -> log.error("Audit insertMany failed — parsed messages left pending for retry.", e))
                .onFailure().recoverWithNull();

        return insertUni.chain(() -> {
            if (!idsToAck.isEmpty()) {
                return redisStream.xack(config.streamKey(), config.streamGroupName(), idsToAck.toArray(new String[0])).replaceWithVoid();
            }
            return Uni.createFrom().voidItem();
        });
    }

    private Document convertBsonToMongoDoc(org.bson.BsonDocument bson) {
        String uuid = bson.containsKey("uuid") && bson.isString("uuid") ? bson.getString("uuid").getValue() : "GLOBAL";
        String module = bson.containsKey("module") && bson.isString("module") ? bson.getString("module").getValue() : "";
        String action = bson.containsKey("action") && bson.isString("action") ? bson.getString("action").getValue() : "";
        String description = bson.containsKey("description") && bson.isString("description") ? bson.getString("description").getValue() : "";
        String level = bson.containsKey("level") && bson.isString("level") ? bson.getString("level").getValue() : "NORMAL";

        long timestampMs = bson.containsKey("timestamp") && bson.isInt64("timestamp")
                ? bson.getInt64("timestamp").getValue()
                : System.currentTimeMillis();

        return new Document("uuid", uuid)
                .append("module", module)
                .append("action", action)
                .append("description", description)
                .append("level", level)
                .append("timestamp", new Date(timestampMs));
    }
}
