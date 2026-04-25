package tech.skworks.tachyon.api.services;

import java.util.concurrent.CompletableFuture;

/**
 * Project Tachyon
 * Class SystemService
 *
 * @author  Jimmy (vSKAH) - 22/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public interface SystemService {

    CompletableFuture<Boolean> pingBackend();

}
