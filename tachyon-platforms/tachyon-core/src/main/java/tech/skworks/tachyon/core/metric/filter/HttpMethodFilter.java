package tech.skworks.tachyon.core.metric.filter;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
public final class HttpMethodFilter extends Filter {

    private final Set<String> allowedMethods;
    private final String allowHeaderValue;

    /**
     * Constructs a new {@link HttpMethodFilter}.
     *
     * @param allowedMethods set of HTTP methods allowed (e.g. GET, HEAD)
     */
    public HttpMethodFilter(@NotNull final Set<String> allowedMethods) {
        Objects.requireNonNull(allowedMethods, "allowedMethods cannot be null");
        this.allowedMethods = allowedMethods.stream()
                .map(method -> method.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.allowHeaderValue = String.join(", ", this.allowedMethods);
    }

    @Override
    public void doFilter(@NotNull final HttpExchange exchange, @NotNull final Chain chain) throws IOException {
        final var method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);

        if (!allowedMethods.contains(method)) {
            exchange.getResponseHeaders().set("Allow", allowHeaderValue);
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        chain.doFilter(exchange);
    }

    @Override
    public String description() {
        return "Restricts HTTP requests to allowed methods: " + allowHeaderValue;
    }
}
