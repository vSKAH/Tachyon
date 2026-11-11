package tech.skworks.tachyon.api.profile.spi;

import org.jetbrains.annotations.NotNull;
import tech.skworks.tachyon.api.component.ComponentRegistry;
import tech.skworks.tachyon.api.profile.TachyonProfile;

/**
 * Service Provider Interface (SPI) for internal engine lifecycle and persistence.
 * <p>
 * Reserved strictly for the internal Tachyon runtime engine (auto-save tasks, handoffs,
 * gRPC serializers). Third-party plugins MUST NOT invoke methods on this interface.
 * Pure contract (zero default code).
 * </p>
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @author Jimmy (vSKAH) - 11/11/2026
 * @version 2.0
 * @since 2.0.0-SNAPSHOT
 */
public interface TachyonProfileSPI extends TachyonProfile {

    /**
     * Returns the ComponentRegistry associated with this profile runtime.
     *
     * @return The ComponentRegistry instance.
     */
    @NotNull ComponentRegistry getRegistry();

    /**
     * Returns true if any slot has been modified or deleted since the last confirmed save.
     *
     * @return true if there are pending unpersisted changes.
     */
    boolean hasPendingChanges();

    /**
     * Freezes the profile to prevent any subsequent mutation.
     */
    void freeze();

    /**
     * Returns whether this profile is frozen.
     *
     * @return true if the profile is frozen, false otherwise.
     */
    boolean isFrozen();
}
