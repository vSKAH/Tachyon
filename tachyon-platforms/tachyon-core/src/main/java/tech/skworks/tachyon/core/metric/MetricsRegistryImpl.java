package tech.skworks.tachyon.core.metric;

import io.micrometer.core.instrument.MeterRegistry;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import tech.skworks.tachyon.api.metrics.MetricsCollector;
import tech.skworks.tachyon.api.metrics.MetricsRegistry;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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
public class MetricsRegistryImpl implements MetricsRegistry {

    private final Set<MetricsCollector> collectors;
    private final MeterRegistry meterRegistry;
    private final AtomicBoolean started;

    public MetricsRegistryImpl(final @NotNull MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry cannot be null");
        this.collectors = ConcurrentHashMap.newKeySet();
        this.started = new AtomicBoolean(false);
    }

    @Override
    public void add(final @NotNull MetricsCollector collector) {
        Objects.requireNonNull(collector, "collector cannot be null");
        collectors.add(collector);
        if (started.get()) collector.start();
    }

    @Override
    public void remove(final @NotNull MetricsCollector collector) {
        Objects.requireNonNull(collector, "collector cannot be null");
        if (collectors.remove(collector))
            collector.stop();
    }

    @Override
    public @NonNull Set<MetricsCollector> getCollectors() {
        return Set.copyOf(collectors);
    }

    public MeterRegistry getMeterRegistry() {
        return meterRegistry;
    }

    public void startAll() {
        started.set(true);
        collectors.forEach(MetricsCollector::start);
    }

    public void stopAll() {
        started.set(false);
        collectors.forEach(MetricsCollector::stop);
    }
}
