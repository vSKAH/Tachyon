package tech.skworks.tachyon.core.metric.filter;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Supplier;

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
public final class MetricsAuthFilter extends Filter {

    private final Supplier<String> tokenSupplier;

    /**
     * Constructs a new {@link MetricsAuthFilter}.
     *
     * @param tokenSupplier supplier returning the expected secret token, or null if disabled
     */
    public MetricsAuthFilter(@NotNull final Supplier<String> tokenSupplier) {
        this.tokenSupplier = Objects.requireNonNull(tokenSupplier, "tokenSupplier cannot be null");
    }

    @Override
    public void doFilter(@NotNull final HttpExchange exchange, @NotNull final Chain chain) throws IOException {
        final var expectedToken = tokenSupplier.get();

        if (expectedToken == null || expectedToken.isBlank()) {
            chain.doFilter(exchange);
            return;
        }

        final var authHeader = exchange.getRequestHeaders().getFirst("Authorization");

        if (authHeader == null || !authHeader.equals("Bearer " + expectedToken)) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"metrics\"");
            exchange.sendResponseHeaders(401, -1);
            return;
        }

        chain.doFilter(exchange);
    }

    @Override
    public String description() {
        return "Secures /metrics endpoint with Bearer token authentication";
    }
}
