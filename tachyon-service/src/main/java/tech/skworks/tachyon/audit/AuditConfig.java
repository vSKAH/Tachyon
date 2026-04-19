package tech.skworks.tachyon.audit;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

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
    @WithDefault("audit_logs") String collection();

    @WithDefault("tachyon:audit_stream") String streamKey();
    @WithDefault("tachyon:audit_group") String streamGroupName();
    @WithDefault("tachyon:audit_worker_1") String consumerId();

}
