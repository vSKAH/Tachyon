package tech.skworks.tachyon;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import org.jboss.logging.Logger;
import tech.skworks.tachyon.infra.SystemGrpcService;

/**
 * Project Tachyon
 * Class TachyonService
 *
 * @author Jimmy Badaire (vSKAH) - 03/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class TachyonService implements QuarkusApplication {

    static void main(String... args) {
        Quarkus.run(TachyonService.class, args);
    }

    @Override
    public int run(String... args) {
        Quarkus.waitForExit();
        return 0;

    }

}
