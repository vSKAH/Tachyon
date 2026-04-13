package tech.skworks.tachyon.plugin.internal.player.retry;

import java.util.UUID;

/**
 * Project Tachyon
 * Class RetryTask
 *
 * @author  Jimmy (vSKAH) - 07/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */

public abstract class RetryTask {

    private final UUID uuid;
    private final long createdAt = System.currentTimeMillis();
    private int attempts = 0;

    protected RetryTask(UUID uuid) {
        this.uuid = uuid;
    }

    public abstract boolean execute();

    public abstract String describe();

    public UUID getUuid() {
        return uuid;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public void incrementAttempts() {
        ++attempts;
    }

    public boolean isExhausted() {
        return attempts > 50 || System.currentTimeMillis() - createdAt > 600_000L;
    }
}
