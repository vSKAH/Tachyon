package tech.skworks.tachyon.api.profile;

import tech.skworks.tachyon.libs.protobuf.Message;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Project Tachyon
 * Class TachyonProfile
 *
 * <p> Component lifecycle: </p>
 * <p> - setComponent()    : called by plugins — sets value AND marks dirty.</p>
 * <p> - saveComponent()   : marks dirty + immediately enqueues a gRPC save.</p>
 * <p> - saveProfile()     : flushes only dirty components to the backend.</p>
 *
 * </p> The dirty set acts as a safety net: if a saveComponent() gRPC call hasn't
 * been confirmed yet when the player disconnects, saveProfile() will catch it. </p>
 *
 * @author  Jimmy (vSKAH) - 09/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public interface TachyonProfile {

    public <T extends Message> void setComponent(T component);

    public <T extends Message, B extends Message.Builder> void updateComponent(Class<T> clazz, Consumer<B> modifier);

    public <T extends Message> T getComponent(Class<T> clazz, @Nonnull T defaultValue);

    public @Nullable <T extends Message> T getComponent(Class<T> clazz);

    public <T extends Message> CompletableFuture<Void> saveComponent(T component);

    public <T extends Message> CompletableFuture<Void> deleteComponent(T defaultValue);

    public CompletableFuture<Void> saveProfile();

    public UUID getUuid();

}
