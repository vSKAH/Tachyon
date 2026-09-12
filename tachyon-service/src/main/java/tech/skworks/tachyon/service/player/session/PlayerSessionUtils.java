package tech.skworks.tachyon.service.player.session;

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
public class PlayerSessionUtils {

    public static final String HEARTBEAT_BATCH_LUA = """
        local ttl = tonumber(ARGV[1])
        for i = 1, #KEYS do
            redis.call('SET', KEYS[i], 'USED', 'EX', ttl)
        end
        return 1
    """;

}
