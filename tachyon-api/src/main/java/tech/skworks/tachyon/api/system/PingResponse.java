package tech.skworks.tachyon.api.system;

/**
 * Detailed telemetry response from a Tachyon backend ping probe.
 *
 * @param clientTime             Timestamp (ms) when the client dispatched the probe.
 * @param serverTime             Timestamp (ms) when the backend handled the probe.
 * @param tachyonServerName      Identifier of the responding Tachyon backend node.
 * @param redisOnline            Whether Redis is reachable and responsive.
 * @param mongoOnline            Whether MongoDB is reachable and responsive.
 * @param healthy                Whether the backend and all required datastores are fully operational.
 *
 * @author Jimmy (vSKAH)
 * @version 1.0
 * @since 2.0.0-SNAPSHOT
 */
public record PingResponse(
        long clientTime,
        long serverTime,
        String tachyonServerName,
        boolean redisOnline,
        boolean mongoOnline,
        boolean healthy
) {

    /**
     * Calculates the total Round-Trip Time (RTT) in milliseconds.
     */
    public long roundTripLatencyMs(long clientReceiveTime) {
        return Math.max(0, clientReceiveTime - clientTime);
    }

    /**
     * Estimates clock skew / drift (in ms) between client and backend server clocks.
     */
    public long clockDriftMs(long clientReceiveTime) {
        if (serverTime <= 0 || clientTime <= 0) return 0;
        long estimatedServerTime = clientTime + (roundTripLatencyMs(clientReceiveTime) / 2);
        return serverTime - estimatedServerTime;
    }
}
