package tech.skworks.tachyon.api;

import tech.skworks.tachyon.api.profile.TachyonProfile;
import tech.skworks.tachyon.libs.protobuf.Message;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Project Tachyon
 * Class TachyonAPI
 *
 * @author  Jimmy (vSKAH) - 13/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public interface TachyonAPI {

    <T extends Message> void registerComponent(T defaultInstance);
    @Nullable TachyonProfile getProfile(UUID uuid);
    void audit(UUID uuid, String action, String details);
    boolean tachyonCoreDisabling();
}
