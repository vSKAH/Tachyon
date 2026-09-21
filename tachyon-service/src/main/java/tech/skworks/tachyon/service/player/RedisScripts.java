package tech.skworks.tachyon.service.player;

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
public class RedisScripts {

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

    public static final String HEARTBEAT_BATCH_LUA = """
                local ttl = tonumber(ARGV[1])
                local server_id = ARGV[2]
                for i = 1, #KEYS do
                    redis.call('SET', KEYS[i], server_id, 'EX', ttl)
                end
                return 1
            """;

    public static final String PULL_PROFILE_LUA = """
            if redis.call('EXISTS', KEYS[1]) == 1 then
                return {'DIRTY', ''}
            end
            
            local current_server = redis.call('GET', KEYS[2])
            
            if not current_server then
                redis.call('SET', KEYS[2], ARGV[1], 'EX', tonumber(ARGV[2]))
                return {'ACQUIRED', ''}
            elseif current_server == ARGV[1] then
                redis.call('EXPIRE', KEYS[2], tonumber(ARGV[2]))
                return {'SAME_SERVER', current_server}
            else
                return {'HANDOFF', current_server}
            end
            """;


}
