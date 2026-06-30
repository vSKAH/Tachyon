package tech.skworks.tachyon.service;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

/**
 * Project Tachyon
 * Class TachyonService
 *
 * @author Jimmy Badaire (vSKAH) - 03/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */

@QuarkusMain
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
