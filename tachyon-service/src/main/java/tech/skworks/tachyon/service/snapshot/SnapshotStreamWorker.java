package tech.skworks.tachyon.service.snapshot;

import com.mongodb.client.model.Filters;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.quarkus.mongodb.reactive.ReactiveMongoCollection;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.stream.*;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import tech.skworks.tachyon.service.player.PlayerConfig;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Project Tachyon
 * Class SnapshotStreamWorker
 * Pure BSON Snapshot Stream Processor
 *
 * @author  Jimmy (vSKAH) - 15/04/2026
 * @version 2.0
 * @since 1.0.0-SNAPSHOT
 */
@ApplicationScoped
public class SnapshotStreamWorker {

    @Inject
    Logger log;

    @Inject
    ReactiveMongoClient mongo;

    @Inject
    SnapshotConfig snapshotConfig;

    @Inject
    PlayerConfig playerConfig;

    @Inject
    MeterRegistry meterRegistry;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String dbName;

    private static final int MAX_RETRIES = 5;
    private final ReactiveStreamCommands<String, String, byte[]> redisStream;

    private ReactiveMongoCollection<Document> snapshotsCollection;
    private ReactiveMongoCollection<Document> playersCollection;
    private Counter dlqCounter;

    public SnapshotStreamWorker(ReactiveRedisDataSource redisDS) {
        this.redisStream = redisDS.stream(String.class, String.class, byte[].class);
    }

    @PostConstruct
    void init() {
        this.snapshotsCollection = mongo.getDatabase(dbName).getCollection(snapshotConfig.collection());
        this.playersCollection = mongo.getDatabase(dbName).getCollection(playerConfig.collection());
        this.dlqCounter = meterRegistry.counter("tachyon_snapshot_dlq_total");
        this.log.infof("[SnapshotStreamWorker] Initialized with consumer ID '%s'.", snapshotConfig.consumerId());
    }

    @Scheduled(every = "1s", delay = 3L, delayUnit = TimeUnit.SECONDS, concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    Uni<Void> processStream() {
        return reclaimAbandoned()
                .chain(this::readFresh)
                .onFailure().invoke(e -> log.error("[SnapshotStreamWorker] Fatal error in stream processing loop.", e))
                .onFailure().recoverWithNull();
    }

    private Uni<Void> reclaimAbandoned() {
        return redisStream.xautoclaim(snapshotConfig.streamKey(), snapshotConfig.streamGroupName(), snapshotConfig.consumerId(),
                        Duration.ofSeconds(30), "0", 50)
                .map(ClaimedMessages::getMessages)
                .chain(this::processBatch);
    }

    private Uni<Void> readFresh() {
        return readStream(">").chain(this::processBatch);
    }

    private Uni<List<StreamMessage<String, String, byte[]>>> readStream(String lastId) {
        return redisStream.xreadgroup(snapshotConfig.streamGroupName(), snapshotConfig.consumerId(), snapshotConfig.streamKey(), lastId, new XReadGroupArgs().count(50));
    }

    private Uni<Void> processBatch(List<StreamMessage<String, String, byte[]>> messages) {
        if (messages == null || messages.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        return Multi.createFrom().iterable(messages)
                .onItem().transformToUni(this::processSafelyWithDLQ).merge(4)
                .collect().asList()
                .chain(processedIds -> {
                    List<String> messagesToAck = processedIds.stream().filter(Objects::nonNull).toList();
                    int failed = messages.size() - messagesToAck.size();

                    if (failed > 0) {
                        log.warnf("[SnapshotStreamWorker] Cycle complete — %d processed, %d delayed/failed (will not be ACKed yet).", messagesToAck.size(), failed);
                    }
                    if (messagesToAck.isEmpty()) {
                        return Uni.createFrom().voidItem();
                    }

                    return redisStream.xack(snapshotConfig.streamKey(), snapshotConfig.streamGroupName(), messagesToAck.toArray(new String[0])).replaceWithVoid();
                });
    }

    private Uni<String> processSafelyWithDLQ(StreamMessage<String, String, byte[]> msg) {
        return processSingleMessage(msg).onFailure().recoverWithUni(err -> handleFailure(msg, err));
    }

    private Uni<String> processSingleMessage(StreamMessage<String, String, byte[]> msg) {
        return Uni.createFrom().deferred(() -> {
            final Map<String, byte[]> payload = msg.payload();

            final String granularity = getString(payload, "granularity");
            final String source = getString(payload, "source");
            final String timestampStr = getString(payload, "timestamp");

            if (granularity == null || source == null || timestampStr == null) {
                log.warnf("[SnapshotStreamWorker] Message %s missing required fields. Poison pill detected. Discarding.", msg.id());
                return Uni.createFrom().item(msg.id());
            }

            long timestamp;
            try {
                timestamp = Long.parseLong(timestampStr);
            } catch (NumberFormatException e) {
                log.warnf("[SnapshotStreamWorker] Message %s has invalid timestamp. Poison pill detected. Discarding.", msg.id());
                return Uni.createFrom().item(msg.id());
            }

            byte[] specificPayloads = payload.get("specific_payload");
            byte[] globalPayload = payload.get("global_payload");

            try {
                if (specificPayloads != null) {
                    RawBsonDocument request = new RawBsonDocument(specificPayloads);
                    return handleComponentSnapshot(granularity, source, timestamp, request).replaceWith(msg.id());
                } else if (globalPayload != null) {
                    RawBsonDocument request = new RawBsonDocument(globalPayload);
                    return handleDatabaseSnapshot(granularity, source, timestamp, request).replaceWith(msg.id());
                }

                log.warnf("[SnapshotStreamWorker] Message %s has no payloads. Poison pill detected. Discarding.", msg.id());
                return Uni.createFrom().item(msg.id());

            } catch (Exception e) {
                log.errorf(e, "[SnapshotStreamWorker] BSON parsing failed for message %s. Poison pill detected. Discarding.", msg.id());
                return Uni.createFrom().item(msg.id());
            }
        });
    }

    private Uni<Void> handleComponentSnapshot(final String granularity, final String source, final long timestamp, final RawBsonDocument request) {
        String uuid = request.getString("uuid").getValue();
        String targetComponent = request.getString("target_component").getValue();
        String triggerType = request.containsKey("trigger_type") ? request.getString("trigger_type").getValue() : "UNKNOWN";
        String reason = request.containsKey("reason") ? request.getString("reason").getValue() : "N/A";

        Document componentsDoc = new Document();
        if (request.containsKey("data")) {
            BsonValue dataVal = request.get("data");
            if (dataVal.isDocument()) {
                componentsDoc.append(targetComponent, Document.parse(dataVal.asDocument().toJson()));
            } else {
                componentsDoc.append(targetComponent, dataVal.toString());
            }
        }

        Document doc = new Document()
                .append("source", source)
                .append("granularity", granularity)
                .append("timestamp", timestamp)
                .append("uuid", uuid)
                .append("trigger_type", triggerType)
                .append("reason", reason)
                .append("target_component", targetComponent)
                .append("components", componentsDoc)
                .append("locked", false);

        return snapshotsCollection.insertOne(doc)
                .invoke(() -> log.infof("[SnapshotStreamWorker] Snapshot inserted for %s (SPECIFIC_COMPONENT: %s).", uuid, targetComponent))
                .replaceWithVoid();
    }

    private Uni<Void> handleDatabaseSnapshot(final String granularity, final String source, final long timestamp, final RawBsonDocument request) {
        String uuid = request.getString("uuid").getValue();
        String triggerType = request.containsKey("trigger_type") ? request.getString("trigger_type").getValue() : "UNKNOWN";
        String reason = request.containsKey("reason") ? request.getString("reason").getValue() : "N/A";

        return playersCollection.find(Filters.eq("uuid", uuid)).collect().first()
                .chain(playerDoc -> {
                    if (playerDoc == null) {
                        return Uni.createFrom().failure(new DeferredSnapshotException(uuid));
                    }

                    Document componentsDoc = playerDoc.containsKey("components") 
                            ? playerDoc.get("components", Document.class) 
                            : new Document();

                    Document doc = new Document()
                            .append("source", source)
                            .append("granularity", granularity)
                            .append("timestamp", timestamp)
                            .append("uuid", uuid)
                            .append("trigger_type", triggerType)
                            .append("reason", reason)
                            .append("components", componentsDoc)
                            .append("locked", false);

                    return snapshotsCollection.insertOne(doc)
                            .invoke(() -> log.infof("[SnapshotStreamWorker] Native BSON Snapshot inserted for %s (FULL).", uuid))
                            .replaceWithVoid();
                });
    }

    private Uni<String> handleFailure(StreamMessage<String, String, byte[]> msg, Throwable err) {
        final boolean deferred = err instanceof DeferredSnapshotException;

        if (deferred) log.debugf("[SnapshotStreamWorker] Message %s deferred: %s", msg.id(), err.getMessage());
        else log.errorf("[SnapshotStreamWorker] Message %s failed: %s", msg.id(), err.getMessage());

        return redisStream.xpending(snapshotConfig.streamKey(), snapshotConfig.streamGroupName(), StreamRange.of(msg.id(), msg.id()), 1)
                .chain(pendingList -> {
                    long deliveryCount = pendingList.isEmpty() ? 1L : pendingList.getFirst().getDeliveryCount();

                    if (deliveryCount < MAX_RETRIES) {
                        log.infof("[SnapshotStreamWorker] Message %s at retry %d/%d — left pending.", msg.id(), deliveryCount, MAX_RETRIES);
                        return Uni.createFrom().nullItem();
                    }

                    if (deferred) {
                        log.warnf("[SnapshotStreamWorker] Message %s skipped after %d attempts — player data never materialized.", msg.id(), deliveryCount);
                        return Uni.createFrom().item(msg.id());
                    }

                    return moveToDlq(msg);
                })
                .onFailure().invoke(e -> log.error("Failed to check pending status", e))
                .onFailure().recoverWithNull();
    }

    private Uni<String> moveToDlq(StreamMessage<String, String, byte[]> msg) {
        return redisStream.xadd(snapshotConfig.dlqStreamKey(), msg.payload())
                .invoke(dlqId -> {
                    dlqCounter.increment();
                    log.errorf("[SnapshotStreamWorker] Message %s reached max retries (%d) — moved to DLQ '%s' (id: %s).",
                            msg.id(), MAX_RETRIES, snapshotConfig.dlqStreamKey(), dlqId);
                })
                .replaceWith(msg.id());
    }

    private static final class DeferredSnapshotException extends RuntimeException {
        DeferredSnapshotException(String uuid) {
            super("Player data not found yet for " + uuid);
        }
    }

    private String getString(Map<String, byte[]> payload, String key) {
        byte[] bytes = payload.get(key);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }
}
