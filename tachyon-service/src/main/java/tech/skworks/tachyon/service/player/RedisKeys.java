package tech.skworks.tachyon.service.player;

import io.smallrye.common.constraint.NotNull;

/**
 * Short class explaination
 * <p>
 * Long class explaination with @link if needed
 * </p>
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @author Jimmy (vSKAH) - 21/09/2026
 * @version 1.0
 * @since 2.0.0-alpha.2
 */
public class RedisKeys {

    private static final String STATE_PREFIX = "player:server:";
    public static final int STATE_TTL_SECONDS = 30;
    public static final String STATE_TTL_SECONDS_STRING = String.valueOf(STATE_TTL_SECONDS);
    public static String server(@NotNull final String uuid) {
        return STATE_PREFIX + uuid;
    }

    private static final String DIRTY_PREFIX = "player:dirty:";
    public static final int DIRTY_TTL_SECONDS = 60;
    public static final String DIRTY_TTL_SECONDS_STRING = String.valueOf(DIRTY_TTL_SECONDS);
    public static String dirty(@NotNull final String uuid) {
        return DIRTY_PREFIX + uuid;
    }

    private static final String CACHE_PREFIX = "player:cache:";
    public static final int CACHE_TTL_SECONDS = 60 * 30;
    public static final String CACHE_TTL_SECONDS_STRING = String.valueOf(CACHE_TTL_SECONDS);

    public static String cache(@NotNull final String uuid) {
        return CACHE_PREFIX + uuid;
    }

    private static final String HANDOFF_SERVER_PREFIX = "handoff:server:";
    public static final int HANDOFF_SERVER_TTL_SECONDS = 60;

    public static String handoffServer(@NotNull final String serverId) {
        return HANDOFF_SERVER_PREFIX + serverId;
    }

    private static final String HANDOFF_CHANNEL_PREFIX = "handoff:channel:";

    public static String handoffPodChannel(@NotNull final String podId) {
        return HANDOFF_CHANNEL_PREFIX + podId;
    }

    private RedisKeys() {}

}
