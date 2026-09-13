package tech.skworks.tachyon.service.player.data;

import io.smallrye.common.constraint.NotNull;

/**
 * Short class explaination
 * <p>
 * Long class explaination with @link if needed
 * </p>
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @author Jimmy (vSKAH) - 12/09/2026
 * @version 1.0
 * @since 2.0.0-alpha.2
 */
public class PlayerDataUtils {

    private PlayerDataUtils() {}

    public static final String DIRTY_INCREMENT_LUA = """
            local count = redis.call('INCR', KEYS[1])
            redis.call('EXPIRE', KEYS[1], ARGV[1])
            return count;
            """;

    public static final String DIRTY_BATCH_INCREMENT_LUA = """
            local ttl = tonumber(ARGV[1])
            for i = 1, #KEYS do
                redis.call('INCR', KEYS[i])
                redis.call('EXPIRE', KEYS[i], ttl)
            end
            return 1
            """;

    public static final String DIRTY_BATCH_DECREMENT_LUA = """
            local ttl = tonumber(ARGV[1])
            
            for i = 1, #KEYS do
                local key = KEYS[i]
                if redis.call('EXISTS', key) == 1 then
                    local rem = redis.call('DECR', key)
                    if rem <= 0 then
                        redis.call('DEL', key)
                    else
                        redis.call('EXPIRE', key, ttl)
                    end
                end
            end
            return 1;
            """;

    public static final int DIRTY_TTL_SECONDS = 60;
    private static final String DIRTY_PREFIX = "player:dirty:";

    public static String dirty(@NotNull final String uuid) {
        return DIRTY_PREFIX + uuid;
    }

    public static final int CACHE_TTL_SECONDS = 60 * 30;
    private static final String CACHE_PREFIX = "player:cache:";

    public static String cache(@NotNull final String uuid) {
        return CACHE_PREFIX + uuid;
    }

}
