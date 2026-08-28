package tech.skworks.tachyon.service.infra;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Project Tachyon
 * Class ConfigStartup
 *
 * Automatically exports the default application.yaml into ./config/application.yaml
 * on startup if not already present, allowing easy external configuration.
 *
 * @author Jimmy (vSKAH)
 * @version 2.0
 * @since 1.0.0-SNAPSHOT
 */
@ApplicationScoped
public class ConfigStartup {

    @Inject
    Logger log;

    void onStart(@Observes StartupEvent ev) {
        try {
            Path configDir = Paths.get("config");
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
            }

            Path targetConfig = configDir.resolve("application.yaml");
            if (!Files.exists(targetConfig)) {
                try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream("application.yaml")) {
                    if (in != null) {
                        Files.copy(in, targetConfig, StandardCopyOption.REPLACE_EXISTING);
                        log.infof("Default configuration successfully exported to '%s'.", targetConfig.toAbsolutePath());
                    } else {
                        log.warn("Could not find default 'application.yaml' resource in classpath to export.");
                    }
                }
            }
        } catch (Exception e) {
            log.warnf(e, "Failed to export default application.yaml to config/ directory: %s", e.getMessage());
        }
    }
}
