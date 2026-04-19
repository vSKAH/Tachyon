package tech.skworks.tachyon.service.audit;

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
import java.util.concurrent.TimeUnit;

/**
 * Project Tachyon
 * Class AuditStartup
 *
 * @author  Jimmy (vSKAH) - 06/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
@ApplicationScoped
class AuditStartup {

    @Inject
    Logger log;
    @Inject
    AuditConfig auditConfig;

    @Inject
    RedisDataSource redisDS;

    @Inject
    MongoClient mongo;
    @ConfigProperty(name = "quarkus.mongodb.database")
    String mongoDatabase;

    void onStart(@Observes StartupEvent ev) {
        MongoDatabase db = mongo.getDatabase(mongoDatabase);

        boolean exists = db.listCollectionNames().into(new ArrayList<>()).contains(auditConfig.collection());
        if (!exists) {
            db.createCollection(auditConfig.collection(), new CreateCollectionOptions().timeSeriesOptions(new TimeSeriesOptions("timestamp").metaField("uuid").granularity(TimeSeriesGranularity.SECONDS)).expireAfter(180L, TimeUnit.DAYS));
        }

        try {
            redisDS.stream(String.class).xgroupCreate(auditConfig.streamKey(), auditConfig.streamGroupName(), "0", new XGroupCreateArgs().mkstream());
            log.infof("Redis Stream [%s] initialized successfully.", auditConfig.streamKey());
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                log.debugf("Redis Stream [%s] already exists!", auditConfig.streamKey());
            } else {
                throw new RuntimeException("Unable to init the Redis Stream for audit", e);
            }
        }
    }
}
