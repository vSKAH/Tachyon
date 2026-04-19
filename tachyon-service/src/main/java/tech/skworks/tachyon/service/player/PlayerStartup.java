package tech.skworks.tachyon.service.player;

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
    PlayerConfig playerConfig;
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
            redisDS.stream(String.class).xgroupCreate(playerConfig.streamKey(), playerConfig.streamGroupName(), "0", new XGroupCreateArgs().mkstream());
            log.infof("Redis Stream [%s] initialized successfully.", playerConfig.streamKey());
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                log.debugf("Redis Stream [%s] already exists!", playerConfig.streamKey());
            } else {
                throw new RuntimeException("Unable to init the Redis Stream for player", e);
            }
        }

        MongoDatabase database = mongo.getDatabase(mongoDatabase);

        boolean exists = database.listCollectionNames().into(new ArrayList<>()).contains(playerConfig.collection());
        if (!exists) {
            database.createCollection(playerConfig.collection());
            database.getCollection(playerConfig.collection()).createIndex(Indexes.ascending("uuid"), new IndexOptions().unique(true));
        }
        log.info("MongoDB indexes for Player module verified/created.");
    }
}
