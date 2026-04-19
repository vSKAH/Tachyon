package tech.skworks.tachyon.service.infra.health;

import com.mongodb.client.MongoClient;
import io.quarkus.redis.datasource.RedisDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

/**
 * Project Tachyon
 * Class TachyonReadinessCheck
 *
 * @author Jimmy (vSKAH) - 03/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
@Readiness
@ApplicationScoped
public class TachyonReadinessCheck implements HealthCheck {

    @Inject
    MongoClient mongo;
    @Inject
    RedisDataSource redis;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String dbName;


    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder responseBuilder = HealthCheckResponse.named("Tachyon Datastores");

        try {
            redis.execute("PING");
            responseBuilder.withData("Redis", "UP");

            mongo.getDatabase(dbName).runCommand(new Document("ping", 1));
            responseBuilder.withData("MongoDB", "UP");

            return responseBuilder.up().build();
        } catch (Exception e) {
            responseBuilder.withData("Error", e.getMessage());
            return responseBuilder.down().build();
        }
    }
}
