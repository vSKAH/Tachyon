package tech.skworks.tachyon.service.util;

import io.smallrye.common.constraint.NotNull;

/**
 * Project Tachyon
 * Class RedisKeys
 *
 * <p>Single source of truth for the player-lifecycle Redis keys and their TTLs.</p>
 *
 * <p>These prefixes and durations used to be duplicated as literals across
 * {@code PlayerDataGrpcService}, {@code PlayerSessionGrpcService} and
 * {@code PlayerDataStreamWorker}. Centralizing them guarantees the
 * cross-server ownership lock ({@link #STATE_PREFIX}) is renewed by the
 * heartbeat with the <em>exact same</em> TTL it is acquired with — the
 * invariant the anti-duplication guarantee depends on.</p>
 *
 * @author  Jimmy (vSKAH)
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public final class RedisKeys {

    /** Cross-server ownership lock: set {@code NX EX} on pull, renewed by heartbeats. */
    private static final String STATE_PREFIX = "player:state:";

    /** Hot read cache of the last known profile. */
    private static final String CACHE_PREFIX = "player:cache:";

    /** TTL (seconds) of the ownership lock — MUST match the heartbeat renewal delay. */
    public static final int STATE_TTL_SECONDS = 30;
    /** TTL (seconds) of the dirty guard. */
    public static final int DIRTY_TTL_SECONDS = 60;
    /** TTL (seconds) of the read cache. */
    public static final int CACHE_TTL_SECONDS = 60 * 30;

    private RedisKeys() {}

    public static String state(@NotNull final String uuid) {
        return STATE_PREFIX + uuid;
    }


    public static String cache(@NotNull final String uuid) {
        return CACHE_PREFIX + uuid;
    }
}
