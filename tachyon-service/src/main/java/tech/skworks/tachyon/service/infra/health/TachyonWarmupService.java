package tech.skworks.tachyon.service.infra.health;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Short class explaination
 * <p>
 * Long class explaination with @link if needed
 * </p>
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @author Jimmy (vSKAH) - 29/08/2026
 * @version 1.0
 * @since 2.0.0-alpha.2
 */
public class TachyonWarmupService {

    @Inject
    Logger log;

    @ConfigProperty(name = "tachyon.warmup.duration", defaultValue = "15")
    long warmupDuration;

    private final AtomicBoolean warmingUp = new AtomicBoolean(true);
    private volatile long warmupEndTime = 0L;


    void onStart(@Observes StartupEvent event) {
        long durationMs = warmupDuration * 1000L;
        this.warmupEndTime = System.currentTimeMillis() + durationMs;
        this.warmingUp.set(true);

        log.infof("[WarmupService] Backend is in RECOVERY WARM-UP mode for %ds. Rejecting player profile pulls to allow DLQ flushes...", warmupDuration);

        Uni.createFrom().nullItem()
                .onItem().delayIt().by(Duration.ofSeconds(warmupDuration))
                .emitOn(Infrastructure.getDefaultWorkerPool())
                .subscribe().with(_ -> {
                    if (warmingUp.compareAndSet(true, false)) {
                        log.infof("[WarmupService] WARM-UP COMPLETED! Backend is now fully OPEN to player logins.");
                    }
                });
    }

    public boolean isWarmingUp() {
        return warmingUp.get();
    }

    public long getRemainingWarmupMs() {
        if (!warmingUp.get()) return 0L;
        long remaining = warmupEndTime - System.currentTimeMillis();
        return Math.max(0L, remaining);
    }

    public void forceComplete() {
        if (warmingUp.compareAndSet(true, false)) {
            log.infof("[WarmupService] Fast-path triggered: Warm-up ended early.");
        }
    }
}
