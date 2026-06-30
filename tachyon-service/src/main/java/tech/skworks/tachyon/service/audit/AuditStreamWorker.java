package tech.skworks.tachyon.service.audit;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.stream.ClaimedMessages;
import io.quarkus.redis.datasource.stream.StreamCommands;
import io.quarkus.redis.datasource.stream.StreamMessage;
import io.quarkus.redis.datasource.stream.XReadGroupArgs;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import tech.skworks.tachyon.service.contracts.audit.AuditLogEntry;
import tech.skworks.tachyon.service.contracts.audit.LogEventBatchRequest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Project Tachyon
 * Class AuditStreamWorker
 *
 * @author  Jimmy (vSKAH) - 06/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
@ApplicationScoped
class AuditStreamWorker {

    @Inject
    Logger log;

    @Inject
    MongoClient mongo;

    @Inject
    AuditConfig config;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String dbName;

    private final StreamCommands<String, String, byte[]> redisStream;

    private MongoCollection<Document> auditCollection;

    public AuditStreamWorker(RedisDataSource redisDS) {
        this.redisStream = redisDS.stream(String.class, String.class, byte[].class);
    }

    @PostConstruct
    void init() {
        this.auditCollection = mongo.getDatabase(dbName).getCollection(config.collection());
        log.infof("[AuditStreamWorker] Initialized with consumer ID '%s' on stream '%s'.", config.consumerId(), config.streamKey());
    }

    @Scheduled(every = "1s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void processAuditStream() {
        try {
            ClaimedMessages<String, String, byte[]> claimed = redisStream.xautoclaim(config.streamKey(), config.streamGroupName(), config.consumerId(), Duration.ofSeconds(30), "0", 100);
            processAuditBatch(claimed.getMessages());

            List<StreamMessage<String, String, byte[]>> fresh = redisStream.xreadgroup(config.streamGroupName(), config.consumerId(), config.streamKey(), ">", new XReadGroupArgs().count(100));
            processAuditBatch(fresh);

        } catch (Exception e) {
            log.error("Error in AuditStreamWorker loop", e);
        }
    }

    private void processAuditBatch(List<StreamMessage<String, String, byte[]>> messages) {
        if (messages == null || messages.isEmpty()) return;

        List<Document> docsToInsert = new ArrayList<>();
        List<String> parsedIds = new ArrayList<>();
        List<String> poisonIds = new ArrayList<>();

        for (StreamMessage<String, String, byte[]> msg : messages) {
            try {
                LogEventBatchRequest batch = LogEventBatchRequest.parseFrom(msg.payload().get("payload"));

                for (AuditLogEntry logItem : batch.getEntriesList()) {
                    docsToInsert.add(new Document("uuid", logItem.getUuid()).append("module", logItem.getModule()).append("action", logItem.getAction()).append("details", logItem.getDetails()).append("timestamp", new Date(logItem.getTimestampMs())));
                }
                parsedIds.add(msg.id());
            } catch (Exception e) {
                log.errorf(e, "Failed to parse audit message %s — poison, dropping (ACK).", msg.id());
                poisonIds.add(msg.id());
            }
        }

        List<String> idsToAck = new ArrayList<>(poisonIds);

        if (!docsToInsert.isEmpty()) {
            try {
                auditCollection.insertMany(docsToInsert);
                idsToAck.addAll(parsedIds);
            } catch (Exception e) {
                log.error("Audit insertMany failed — parsed messages left pending for retry.", e);
            }
        }

        if (!idsToAck.isEmpty()) {
            redisStream.xack(config.streamKey(), config.streamGroupName(), idsToAck.toArray(new String[0]));
        }
    }
}
