package tech.skworks.tachyon.service.snapshot;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.SetArgs;
import io.quarkus.redis.datasource.value.ValueCommands;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Project Tachyon
 * Class OffPeakS3Janitor
 *
 * @author Jimmy (vSKAH) - 03/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
@ApplicationScoped
public class SnapshotS3Archiver {

    @Inject
    Logger log;
    @Inject
    MongoClient mongo;
    @Inject
    S3Client s3;
    @Inject
    SnapshotConfig config;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String dbName;

    private ValueCommands<String, String> redisString;
    private MongoCollection<Document> snapshotCollection;

    public SnapshotS3Archiver(RedisDataSource redisDS) {
        this.redisString = redisDS.value(String.class);
    }

    @PostConstruct
    void init() {
        this.snapshotCollection = mongo.getDatabase(dbName).getCollection(config.collection());
        this.log.debug("MongoDB collections initialized for SnapshotS3Archiver.");
    }

    private boolean isOffPeak() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        int hour = now.getHour();
        DayOfWeek day = now.getDayOfWeek();

        if (hour >= 23 || hour < 8) return true;
        return hour >= 14 && hour < 17 && day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }

    @Scheduled(cron = "0 0 * * * ?", delay = 10L, delayUnit = TimeUnit.SECONDS, concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void executeArchivingJob() {
        if (!isOffPeak()) return;

        final String lockKey = "tachyon:job:s3_archiver:lock";
        final String lockValue = UUID.randomUUID().toString();
        boolean lockAcquired = redisString.setAndChanged(lockKey, lockValue, new SetArgs().nx().ex(7200));

        if (!lockAcquired) {
            log.info("[S3Archiver] Already running on another instance — skipped.");
            return;
        }

        log.infof("[S3Archiver] Starting — archiving snapshots older than %d day(s)...", config.retentionDays());

        long cutoffTimestamp = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(config.retentionDays());

        Semaphore uploadThrottle = new Semaphore(config.maxConcurrentUploads());
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        try {
            try (MongoCursor<Document> cursor = snapshotCollection.find(Filters.and(Filters.lt("timestamp", cutoffTimestamp), Filters.exists("archived_at", false))).batchSize(50).iterator()) {

                while (cursor.hasNext()) {
                    Document snapshot = cursor.next();
                    String uuid = snapshot.getString("uuid");
                    String snapId = snapshot.getObjectId("_id").toHexString();
                    String key = "snapshots/" + uuid + "/" + snapId + ".json";

                    uploadThrottle.acquire();

                    Thread.startVirtualThread(() -> {
                        try {
                            log.debugf("[S3Archiver] Uploading snapshot %s for player %s...", snapId, uuid);

                            PutObjectRequest putReq = PutObjectRequest.builder()
                                    .bucket(config.s3Bucket())
                                    .key(key)
                                    .contentType("application/json")
                                    .build();

                            s3.putObject(putReq, RequestBody.fromString(snapshot.toJson()));
                            snapshotCollection.updateOne(Filters.eq("_id", snapshot.getObjectId("_id")), Updates.set("archived_at", new Date()));

                            successCount.incrementAndGet();
                            log.debugf("[S3Archiver] Snapshot %s has been marked as archived (player %s).", snapId, uuid);
                        } catch (Exception ex) {
                            log.errorf(ex, "[S3Archiver] Processing failed for snapshot %s (player %s).", snapId, uuid);
                            failCount.incrementAndGet();
                        } finally {
                            uploadThrottle.release();
                        }
                    });
                }
            }

            log.debugf("[S3Archiver] Waiting for %d in-flight upload(s) to complete...", config.maxConcurrentUploads() - uploadThrottle.availablePermits());
            uploadThrottle.acquireUninterruptibly(config.maxConcurrentUploads());

        } catch (Exception e) {
            log.errorf(e, "[S3Archiver] Unexpected error — waiting for in-flight uploads before releasing lock.");
            uploadThrottle.acquireUninterruptibly(config.maxConcurrentUploads());
        } finally {
            if (lockValue.equals(redisString.get(lockKey))) {
                redisString.getdel(lockKey);
            }
            log.infof("[S3Archiver] Finished. Success: %d | Failed: %d | Lock released.", successCount.get(), failCount.get());
        }
    }
}
