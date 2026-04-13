package tech.skworks.tachyon.audit;

import io.smallrye.config.ConfigMapping;

/**
 * Project Tachyon
 * Class AuditConfig
 *
 * @author  Jimmy (vSKAH) - 06/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
@ConfigMapping(prefix = "tachyon.audit")
public interface AuditConfig {
    String streamKey();
    String collection();
}
