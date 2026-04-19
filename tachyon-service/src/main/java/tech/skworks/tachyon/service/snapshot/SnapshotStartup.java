package tech.skworks.tachyon.service.snapshot;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.stream.XGroupCreateArgs;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Project Tachyon
 * Class SnapshotStartup
 *
 * @author  Jimmy (vSKAH) - 06/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
@ApplicationScoped
public class SnapshotStartup {

    @Inject
    SnapshotConfig snapshotConfig;
    @Inject
    Logger log;

    @Inject
    RedisDataSource redisDS;

    @Inject
    MongoClient mongo;
    @ConfigProperty(name = "quarkus.mongodb.database")
    String mongoDatabase;

    void onStart(@Observes StartupEvent ev) {

        try {
            redisDS.stream(String.class).xgroupCreate(snapshotConfig.streamKey(), snapshotConfig.streamGroupName(), "0", new XGroupCreateArgs().mkstream());
            log.infof("Redis Stream [%s] initialized successfully.", snapshotConfig.streamKey());
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                log.debugf("Redis Stream [%s] already exists!", snapshotConfig.streamKey());
            } else {
                throw new RuntimeException("Unable to init the Redis Stream for player", e);
            }
        }

        MongoDatabase database = mongo.getDatabase(mongoDatabase);

        boolean exists = database.listCollectionNames().into(new ArrayList<>()).contains(snapshotConfig.collection());
        if (!exists) {
            database.createCollection(snapshotConfig.collection());
            MongoCollection<Document> snapshotCollection = database.getCollection(snapshotConfig.collection());
            snapshotCollection.createIndex(Indexes.compoundIndex(Indexes.ascending("uuid"), Indexes.descending("timestamp")));
            IndexOptions options = new IndexOptions().expireAfter(1L, TimeUnit.DAYS).partialFilterExpression(Filters.eq("locked", false));
            snapshotCollection.createIndex(Indexes.ascending("archived_at"), options);
        }
        log.info("MongoDB indexes for Snapshot module verified/created.");
    }

}
