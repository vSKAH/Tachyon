package tech.skworks.tachyon.snapshot;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Indexes;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import tech.skworks.tachyon.player.PlayerConfig;

import java.util.ArrayList;

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
    SnapshotConfig config;
    @Inject
    Logger log;

    @Inject
    MongoClient mongo;
    @ConfigProperty(name = "tachyon.database.name")
    String mongoDatabase;

    void onStart(@Observes StartupEvent ev) {
        MongoDatabase database = mongo.getDatabase(mongoDatabase);

        boolean exists = database.listCollectionNames().into(new ArrayList<>()).contains(config.snapshotsCollection());
        if (!exists) {
            database.createCollection(config.snapshotsCollection());
            database.getCollection(config.snapshotsCollection()).createIndex(Indexes.compoundIndex(Indexes.ascending("uuid"), Indexes.descending("timestamp")));
        }
        log.info("MongoDB indexes for Snapshot module verified/created.");
    }

}
