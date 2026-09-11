package tech.skworks.tachyon.plugin.core.resilience;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tech.skworks.tachyon.plugin.core.metric.scraper.TachyonMetrics;
import tech.skworks.tachyon.plugin.core.util.TachyonLogger;
import tech.skworks.tachyon.plugin.spigot.TachyonCore;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Centralized, instance-based Resilience manager for Tachyon.
 * <p>
 * Manages isolated {@link CircuitBreaker} and {@link Retry} instances per protected service ID
 * (e.g. "player-data", "audit", "snapshot", "player-session") adhering to Resilience4j's Golden Rule.
 * </p>
 * <p>
 * Contains zero static mutable state: clean lifecycle management, testable in isolation,
 * and seamlessly hooked to proactive health probes and Micrometer metrics.
 * </p>
 *
 * @author Jimmy (vSKAH) - 10/09/2026
 * @version 2.0
 * @since 2.0.0-alpha.2
 */
public final class ResilienceService {

    private static final TachyonLogger LOGGER = TachyonCore.getModuleLogger("Resilience");

    /**
     * Predicate identifying transient gRPC network or server availability failures.
     * Deterministic business errors (e.g. INVALID_ARGUMENT, NOT_FOUND, PERMISSION_DENIED)
     * are filtered out to avoid false-positive circuit breaker trips.
     */
    public static final Predicate<Throwable> IS_TRANSIENT_GRPC_ERROR = throwable -> {
        if (throwable instanceof StatusRuntimeException sre) {
            Status.Code code = sre.getStatus().getCode();
            return switch (code) {
                case UNAVAILABLE, DEADLINE_EXCEEDED, RESOURCE_EXHAUSTED, ABORTED, INTERNAL, UNKNOWN -> true;
                default -> false;
            };
        }
        return throwable instanceof IOException || throwable instanceof TimeoutException;
    };

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;
    private final AtomicBoolean backendHealthy = new AtomicBoolean(true);

    public ResilienceService(@Nullable final TachyonMetrics metrics) {
        final CircuitBreakerConfig defaultCbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50.0f)
                .slowCallRateThreshold(50.0f)
                .slowCallDurationThreshold(Duration.ofSeconds(3))
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(3)
                .recordException(IS_TRANSIENT_GRPC_ERROR)
                .ignoreException(t -> !IS_TRANSIENT_GRPC_ERROR.test(t))
                .build();

        final RetryConfig defaultRetryConfig = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(200))
                .retryOnException(IS_TRANSIENT_GRPC_ERROR)
                .build();

        this.circuitBreakerRegistry = CircuitBreakerRegistry.of(defaultCbConfig);
        this.retryRegistry = RetryRegistry.of(defaultRetryConfig);

        if (metrics != null && metrics.getRegistry() != null) {
            try {
                TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(this.circuitBreakerRegistry).bindTo(metrics.getRegistry());
                TaggedRetryMetrics.ofRetryRegistry(this.retryRegistry).bindTo(metrics.getRegistry());
                LOGGER.info("Resilience4j metrics registered into Micrometer registry.");
            } catch (Exception e) {
                LOGGER.error(e, "Failed to bind Resilience4j metrics to Micrometer.");
            }
        }
    }

    /**
     * Obtains or creates the unique CircuitBreaker for a protected service ID.
     */
    public @NotNull CircuitBreaker circuitBreaker(@NotNull final String serviceId) {
        return circuitBreakerRegistry.circuitBreaker(Objects.requireNonNull(serviceId, "serviceId cannot be null"));
    }

    /**
     * Obtains or creates the unique Retry for a protected service ID.
     */
    public @NotNull Retry retry(@NotNull final String serviceId) {
        return retryRegistry.retry(Objects.requireNonNull(serviceId, "serviceId cannot be null"));
    }

    /**
     * Proactive hook called by {@code HealthServiceImpl} when the backend probe state changes.
     *
     * @param healthy true if Quarkus, Redis, and Mongo are healthy; false if ping failed or services are down
     */
    public void setBackendHealthy(final boolean healthy) {
        final boolean previous = backendHealthy.getAndSet(healthy);
        if (previous == healthy) {
            return;
        }

        if (!healthy) {
            LOGGER.warn("Proactive health notification: Quarkus backend is UNHEALTHY. Tripping operational circuit breakers to OPEN.");
            circuitBreakerRegistry.getAllCircuitBreakers().forEach(cb -> {
                if (!cb.getName().equalsIgnoreCase("health")) {
                    try {
                        cb.transitionToOpenState();
                    } catch (Exception e) {
                        LOGGER.debug("Could not trip circuit breaker '{}' to OPEN: {}", cb.getName(), e.getMessage());
                    }
                }
            });
        } else {
            LOGGER.info("Proactive health notification: Quarkus backend is BACK ONLINE. Transitioning circuit breakers to HALF_OPEN.");
            circuitBreakerRegistry.getAllCircuitBreakers().forEach(cb -> {
                if (cb.getState() == CircuitBreaker.State.OPEN) {
                    try {
                        cb.transitionToHalfOpenState();
                    } catch (Exception e) {
                        LOGGER.debug("Could not transition circuit breaker '{}' to HALF_OPEN: {}", cb.getName(), e.getMessage());
                    }
                }
            });
        }
    }

    /**
     * Checks whether the backend is globally healthy according to proactive health monitoring.
     */
    public boolean isBackendHealthy() {
        return backendHealthy.get();
    }

    /**
     * Checks whether a specific service is available to receive calls.
     * The "health" probe service is always considered available to allow continuous polling.
     */
    public boolean isAvailable(@NotNull final String serviceId) {
        if ("health".equalsIgnoreCase(serviceId)) {
            return true;
        }
        return backendHealthy.get() && circuitBreaker(serviceId).getState() != CircuitBreaker.State.OPEN;
    }

    public @NotNull CircuitBreakerRegistry getCircuitBreakerRegistry() {
        return circuitBreakerRegistry;
    }

    public @NotNull RetryRegistry getRetryRegistry() {
        return retryRegistry;
    }

    /**
     * Executes an action protected by the specified service's CircuitBreaker and Retry policies.
     */
    public <T> T execute(@NotNull final String serviceId, @NotNull final Callable<T> action) throws Exception {
        if (!isAvailable(serviceId)) {
            throw CallNotPermittedException.createCallNotPermittedException(circuitBreaker(serviceId));
        }

        Callable<T> decorated = CircuitBreaker.decorateCallable(circuitBreaker(serviceId), action);
        decorated = Retry.decorateCallable(retry(serviceId), decorated);
        return decorated.call();
    }

    /**
     * Executes an action protected by resilience policies, invoking the fallback function on failure.
     */
    public <T> T execute(@NotNull final String serviceId, @NotNull final Callable<T> action, @NotNull final Function<Throwable, T> fallback) {
        try {
            return execute(serviceId, action);
        } catch (Throwable ex) {
            return fallback.apply(ex);
        }
    }

    /**
     * Executes a runnable task protected by the specified service's CircuitBreaker and Retry policies.
     */
    public void executeRunnable(@NotNull final String serviceId, @NotNull final Runnable action) throws Exception {
        execute(serviceId, () -> {
            action.run();
            return null;
        });
    }

    /**
     * Executes a runnable task protected by resilience policies, invoking the fallback consumer on failure.
     */
    public void executeRunnable(@NotNull final String serviceId, @NotNull final Runnable action, @NotNull final Consumer<Throwable> fallback) {
        execute(serviceId, () -> {
            action.run();
            return null;
        }, ex -> {
            fallback.accept(ex);
            return null;
        });
    }
}
