package tech.skworks.tachyon.service.audit;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import io.quarkus.redis.datasource.RedisDataSource;
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
import tech.skworks.tachyon.service.contracts.audit.LogBatchRequest;
import tech.skworks.tachyon.service.contracts.audit.LogRequest;

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
            List<StreamMessage<String, String, byte[]>> messages = redisStream.xreadgroup(config.streamGroupName(), config.consumerId(), config.streamKey(), ">", new XReadGroupArgs().count(100));

            if (messages == null || messages.isEmpty()) return;

            List<Document> docsToInsert = new ArrayList<>();
            List<String> idsToAck = new ArrayList<>();

            for (StreamMessage<String, String, byte[]> msg : messages) {
                try {
                    LogBatchRequest batch = LogBatchRequest.parseFrom(msg.payload().get("payload"));

                    for (LogRequest logItem : batch.getLogsList()) {
                        docsToInsert.add(new Document("uuid", logItem.getUuid()).append("module", logItem.getModule()).append("action", logItem.getAction()).append("details", logItem.getDetails()).append("timestamp", new Date(logItem.getTimestampMs())));
                    }
                    idsToAck.add(msg.id());
                } catch (Exception e) {
                    log.errorf(e, "Failed to parse audit message %s", msg.id());
                }
            }

            if (!docsToInsert.isEmpty()) {
                auditCollection.insertMany(docsToInsert);
            }

            if (!idsToAck.isEmpty()) {
                redisStream.xack(config.streamKey(), config.streamGroupName(), idsToAck.toArray(new String[0]));
            }

        } catch (Exception e) {
            log.error("Error in AuditStreamWorker loop", e);
        }
    }
}
