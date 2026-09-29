package tech.skworks.tachyon.api.snapshot;

import org.jetbrains.annotations.NotNull;

import java.time.LocalDate;

/**
 * Short class explaination
 * <p>
 * Long class explaination with @link if needed
 * </p>
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @author Jimmy (vSKAH) - 24/08/2026
 * @version 1.0
 * @since 2.0.0-alpha.2
 */
public record SnapshotDayInfo(
        @NotNull LocalDate date,
        int count,
        long lastTimestamp
) {}
