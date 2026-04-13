package tech.skworks.tachyon.infra.health;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

/**
 * Project Tachyon
 * Class TachyonLivenessCheck
 *
 * @author Jimmy (vSKAH) - 03/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
@Liveness
@ApplicationScoped
public class TachyonLivenessCheck implements HealthCheck {

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.up("Tachyon Service");
    }
}
