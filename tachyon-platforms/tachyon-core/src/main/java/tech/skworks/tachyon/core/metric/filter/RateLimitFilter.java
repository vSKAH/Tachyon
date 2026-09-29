package tech.skworks.tachyon.core.metric.filter;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

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
public final class RateLimitFilter extends Filter {

    private final long minIntervalNanos;
    private final long retryAfterSeconds;
    private final AtomicLong lastRequestNanos;

    /**
     * Constructs a new {@link RateLimitFilter}.
     *
     * @param minInterval the minimum duration between allowed requests
     */
    public RateLimitFilter(@NotNull final Duration minInterval) {
        Objects.requireNonNull(minInterval, "minInterval cannot be null");
        this.minIntervalNanos = minInterval.toNanos();
        this.retryAfterSeconds = Math.max(1, minInterval.toSeconds());
        this.lastRequestNanos = new AtomicLong(System.nanoTime() - this.minIntervalNanos);
    }

    @Override
    public void doFilter(@NotNull final HttpExchange exchange, @NotNull final Chain chain) throws IOException {
        final var now = System.nanoTime();
        final var last = lastRequestNanos.get();

        if (now - last < minIntervalNanos || !lastRequestNanos.compareAndSet(last, now)) {
            exchange.getResponseHeaders().set("Retry-After", String.valueOf(retryAfterSeconds));
            exchange.sendResponseHeaders(429, -1);
            return;
        }

        chain.doFilter(exchange);
    }

    @Override
    public String description() {
        return "Throttles requests to a minimum interval of " + retryAfterSeconds + "s";
    }
}
