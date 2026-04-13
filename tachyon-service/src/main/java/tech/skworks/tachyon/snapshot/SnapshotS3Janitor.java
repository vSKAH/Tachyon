package tech.skworks.tachyon.snapshot;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Filters;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.SetArgs;
import io.quarkus.redis.datasource.value.ValueCommands;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.Binary;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
public class SnapshotS3Janitor {

    @Inject
    Logger log;
    @Inject
    MongoClient mongo;
    @Inject
    S3AsyncClient s3;
    @Inject
    SnapshotConfig config;

    @ConfigProperty(name = "tachyon.database.name")
    String dbName;

    private ValueCommands<String, String> redisString;
    private MongoCollection<Document> snapshotCollection;

    public SnapshotS3Janitor(RedisDataSource redisDS) {
        this.redisString = redisDS.value(String.class);
    }

    @PostConstruct
    void init() {
        this.snapshotCollection = mongo.getDatabase(dbName).getCollection(config.snapshotsCollection());
        this.log.debug("MongoDB collections initialized for SnapshotS3Janitor.");
    }

    private boolean isOffPeak() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Europe/Paris"));
        int hour = now.getHour();
        DayOfWeek day = now.getDayOfWeek();

        if (hour >= 23 || hour < 8) return true;
        return hour >= 14 && hour < 17 && day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }

    @Scheduled(cron = "0 0 * * * ?", delay = 3L, delayUnit = TimeUnit.SECONDS, concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void executeArchivingJob() {
        if (!isOffPeak()) return;

        String lockKey = "tachyon:job:s3_janitor:lock";
        boolean lockAcquired = redisString.setAndChanged(lockKey, "running", new SetArgs().nx().ex(7200));

        if (!lockAcquired) {
            log.info("[S3Janitor] Already running on another instance — skipped.");
            return;
        }

        log.infof("[S3Janitor] Starting — archiving snapshots older than %d day(s)...", config.retentionDays());

        long cutoffTimestamp = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(config.retentionDays());

        Semaphore uploadThrottle = new Semaphore(config.maxConcurrentUploads());
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        try {
            try (MongoCursor<Document> cursor = snapshotCollection.find(Filters.lt("timestamp", cutoffTimestamp)).batchSize(50).iterator()) {

                while (cursor.hasNext()) {
                    Document snapshot = cursor.next();
                    String uuid = snapshot.getString("uuid");
                    String snapId = snapshot.getObjectId("_id").toHexString();
                    byte[] data = snapshot.get("data", Binary.class).getData();
                    String key = "snapshots/" + uuid + "/" + snapId + ".json";

                    uploadThrottle.acquire();
                    log.debugf("[S3Janitor] Uploading snapshot %s for player %s (%d bytes)...", snapId, uuid, data.length);

                    try {
                        PutObjectRequest putReq = PutObjectRequest.builder().bucket(config.s3Bucket()).key(key).build();

                        s3.putObject(putReq, AsyncRequestBody.fromBytes(data)).whenCompleteAsync((res, err) -> {
                            try {
                                if (err != null) {
                                    log.errorf(err, "[S3Janitor] Upload failed for snapshot %s (player %s).", snapId, uuid);
                                    failCount.incrementAndGet();
                                    return;
                                }

                                try {
                                    snapshotCollection.deleteOne(Filters.eq("_id", snapshot.getObjectId("_id")));
                                    successCount.incrementAndGet();
                                    log.debugf("[S3Janitor] Snapshot %s archived and deleted (player %s).", snapId, uuid);
                                } catch (Exception deleteEx) {
                                    log.errorf(deleteEx, "[S3Janitor] Upload succeeded but MongoDB delete failed for %s", snapId);
                                    failCount.incrementAndGet();
                                }
                            } finally {
                                uploadThrottle.release();
                            }
                        });

                    } catch (Exception syncEx) {
                        uploadThrottle.release();
                        log.errorf(syncEx, "[S3Janitor] Synchronous exception while launching S3 upload for %s", snapId);
                        failCount.incrementAndGet();
                    }
                }
            }

            log.debugf("[S3Janitor] Waiting for %d in-flight upload(s) to complete...", config.maxConcurrentUploads() - uploadThrottle.availablePermits());
            uploadThrottle.acquireUninterruptibly(config.maxConcurrentUploads());

        } catch (Exception e) {
            log.errorf(e, "[S3Janitor] Unexpected error — waiting for in-flight uploads before releasing lock.");
            uploadThrottle.acquireUninterruptibly(config.maxConcurrentUploads());
        } finally {
            redisString.getdel(lockKey);
            log.infof("[S3Janitor] Finished. Success: %d | Failed: %d | Lock released.", successCount.get(), failCount.get());
        }
    }
}
