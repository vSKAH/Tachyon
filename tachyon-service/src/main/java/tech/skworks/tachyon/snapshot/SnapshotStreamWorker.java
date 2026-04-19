package tech.skworks.tachyon.snapshot;

import com.github.luben.zstd.Zstd;
import com.google.protobuf.InvalidProtocolBufferException;
import com.mongodb.client.model.Filters;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.quarkus.mongodb.reactive.ReactiveMongoCollection;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.stream.*;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.Binary;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import tech.skworks.tachyon.contracts.snapshot.SnapshotRequest;
import tech.skworks.tachyon.contracts.snapshot.SpecificSnapshotRequest;
import tech.skworks.tachyon.player.PlayerConfig;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Project Tachyon
 * Class SnapshotStreamWorker
 *
 * @author  Jimmy (vSKAH) - 15/04/2026
 * @version 1.0
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

    @ConfigProperty(name = "quarkus.mongodb.database")
    String dbName;

    private static final int MAX_RETRIES = 5;
    private final ReactiveStreamCommands<String, String, byte[]> redisStream;

    private ReactiveMongoCollection<Document> snapshotsCollection;
    private ReactiveMongoCollection<Document> playersCollection;

    public SnapshotStreamWorker(ReactiveRedisDataSource redisDS) {
        this.redisStream = redisDS.stream(String.class, String.class, byte[].class);
    }

    @PostConstruct
    void init() {
        this.snapshotsCollection = mongo.getDatabase(dbName).getCollection(snapshotConfig.collection());
        this.playersCollection = mongo.getDatabase(dbName).getCollection(playerConfig.collection());
        this.log.infof("[SnapshotStreamWorker] Initialized with consumer ID '%s'.", snapshotConfig.consumerId());
    }

    @Scheduled(every = "1s", delay = 3L, delayUnit = TimeUnit.SECONDS, concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    Uni<Void> processStream() {
        return readStream("0")
                .chain(messages -> {
                    if (messages == null || messages.isEmpty()) {
                        return readStream(">");
                    }
                    log.infof("[SnapshotStreamWorker] Recovering %d pending messages from PEL.", messages.size());
                    return Uni.createFrom().item(messages);
                })
                .chain(this::processBatch)
                .onFailure().invoke(e -> log.error("[SnapshotStreamWorker] Fatal error in stream processing loop.", e))
                .onFailure().recoverWithNull();
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
                    SpecificSnapshotRequest request = SpecificSnapshotRequest.parseFrom(specificPayloads);
                    return handleSpecificSnapshot(granularity, source, timestamp, request).replaceWith(msg.id());
                } else if (globalPayload != null) {
                    SnapshotRequest request = SnapshotRequest.parseFrom(globalPayload);
                    return handleFullSnapshot(granularity, source, timestamp, request).replaceWith(msg.id());
                }

                log.warnf("[SnapshotStreamWorker] Message %s has no payloads. Poison pill detected. Discarding.", msg.id());
                return Uni.createFrom().item(msg.id());

            } catch (InvalidProtocolBufferException e) {
                log.errorf("[SnapshotStreamWorker] Protobuf parsing failed for message %s. Poison pill detected. Discarding.", msg.id());
                return Uni.createFrom().item(msg.id());
            }
        });
    }

    private Uni<Void> handleSpecificSnapshot(final String granularity, final String source, final long timestamp, final SpecificSnapshotRequest request) {
        String uuid = request.getUuid();
        byte[] data = request.getRawData().toByteArray();
        String targetComponent = request.getTargetComponent();

        Document doc = new Document()
                .append("source", source)
                .append("granularity", granularity)
                .append("timestamp", timestamp)
                .append("uuid", uuid)
                .append("trigger_type", request.getTriggerType())
                .append("reason", request.getReason())
                .append("target_component", targetComponent)
                .append("data", new Binary(data));

        return snapshotsCollection.insertOne(doc)
                .invoke(() -> log.infof("[SnapshotStreamWorker] Snapshot inserted for %s (SPECIFIC, size: %d bytes).", uuid, data.length))
                .replaceWithVoid();
    }

    private Uni<Void> handleFullSnapshot(final String granularity, final String source, final long timestamp, final SnapshotRequest request) {
        String uuid = request.getUuid();

        return playersCollection.find(Filters.eq("uuid", uuid)).collect().first()
                .chain(playerDoc -> {
                    if (playerDoc == null) {
                        return Uni.createFrom().failure(new IllegalStateException("Player data not found yet for " + uuid));
                    }

                    return Uni.createFrom()
                            .item(() -> {
                                byte[] rawData = playerDoc.toJson().getBytes(StandardCharsets.UTF_8);
                                return Zstd.compress(rawData);
                            })
                            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                            .chain(compressedData -> {
                                Document doc = new Document()
                                        .append("source", source)
                                        .append("granularity", granularity)
                                        .append("timestamp", timestamp)
                                        .append("uuid", uuid)
                                        .append("trigger_type", request.getTriggerType())
                                        .append("reason", request.getReason())
                                        .append("data", new Binary(compressedData));

                                return snapshotsCollection.insertOne(doc)
                                        .invoke(() -> log.infof("[SnapshotStreamWorker] Snapshot inserted for %s (FULL, size: %d bytes).", uuid, compressedData.length))
                                        .replaceWithVoid();
                            });
                });
    }

    //TODO: add dlq
    private Uni<String> handleFailure(StreamMessage<String, String, byte[]> msg, Throwable err) {
        log.errorf("[SnapshotStreamWorker] Message %s failed: %s", msg.id(), err.getMessage());


        return redisStream.xpending(snapshotConfig.streamKey(), snapshotConfig.streamGroupName(), StreamRange.of(msg.id(), msg.id()), 1)
                .chain(pendingList -> {
                    long deliveryCount = pendingList.isEmpty() ? 1L : pendingList.getFirst().getDeliveryCount();

                    if (deliveryCount >= MAX_RETRIES) {
                        log.errorf("[SnapshotStreamWorker] Message %s reached max retries (%d).", msg.id(), MAX_RETRIES);
                        return Uni.createFrom().item(msg.id());
                    }

                    log.infof("[SnapshotStreamWorker] Message %s is at retry %d/%d.", msg.id(), deliveryCount, MAX_RETRIES);
                    return Uni.createFrom().nullItem();
                })
                .onFailure().invoke(e -> log.error("Failed to check pending status", e))
                .onFailure().recoverWithNull();
    }

    private String getString(Map<String, byte[]> payload, String key) {
        byte[] bytes = payload.get(key);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }
}
