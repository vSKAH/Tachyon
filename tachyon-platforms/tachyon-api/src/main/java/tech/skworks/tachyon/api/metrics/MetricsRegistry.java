package tech.skworks.tachyon.api.metrics;

import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * Short class explaination
 * <p>
 * Long class explaination with @link if needed
 * </p>
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @author Jimmy (vSKAH) - 13/09/2026
 * @version 1.0
 * @since 2.0.0-alpha.2
 */
public interface MetricsRegistry {

    void add(final @NotNull MetricsCollector collector);

    void remove(final @NotNull MetricsCollector collector);

    @NotNull Set<MetricsCollector> getCollectors();
}
