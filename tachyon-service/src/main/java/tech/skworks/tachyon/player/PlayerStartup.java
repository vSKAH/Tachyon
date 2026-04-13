package tech.skworks.tachyon.player;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.*;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.stream.XGroupCreateArgs;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;

/**
 * Project Tachyon
 * Class PlayerStartup
 *
 * @author  Jimmy (vSKAH) - 06/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
@ApplicationScoped
class PlayerStartup {

    @Inject
    PlayerConfig config;
    @Inject
    Logger log;
    @Inject
    RedisDataSource redisDS;

    @Inject
    MongoClient mongo;
    @ConfigProperty(name = "tachyon.database.name")
    String mongoDatabase;

    void onStart(@Observes StartupEvent ev) {
        try {
            redisDS.stream(String.class).xgroupCreate(config.streamKey(), "tachyon_workers", "0", new XGroupCreateArgs().mkstream());
            log.info("Redis Stream [" + config.streamKey() + "] initialized successfully.");
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                log.debug("Redis Stream [" + config.streamKey() + "] already exists!");
            } else {
                throw new RuntimeException("Unable to init the Redis Stream for player", e);
            }
        }

        MongoDatabase database = mongo.getDatabase(mongoDatabase);

        boolean exists = database.listCollectionNames().into(new ArrayList<>()).contains(config.collection());
        if (!exists) {
            database.createCollection(config.collection());
            database.getCollection(config.collection()).createIndex(Indexes.ascending("uuid"), new IndexOptions().unique(true));
        }
        log.info("MongoDB indexes for Player module verified/created.");
    }
}
