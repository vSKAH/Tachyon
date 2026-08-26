package tech.skworks.tachyon.api.system;

import java.util.concurrent.CompletableFuture;

/**
 * Project Tachyon
 * Class SystemService
 *
 * @author  Jimmy (vSKAH) - 22/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public interface HealthService {

    CompletableFuture<PingResponse> pingBackend();

    boolean isHealthy();

}
