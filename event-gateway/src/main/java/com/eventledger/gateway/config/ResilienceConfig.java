package com.eventledger.gateway.config;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;

/**
 * Wires the resiliency stack for calls to the Account Service and binds its metrics into Micrometer
 * (exposed at {@code /actuator/prometheus}).
 */
@Configuration
public class ResilienceConfig {

    public static final String INSTANCE = "accountService";
    private static final Logger log = LoggerFactory.getLogger(ResilienceConfig.class);

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry(
            @Value("${resilience.cb.sliding-window-size:5}") int slidingWindowSize,
            @Value("${resilience.cb.minimum-calls:3}") int minimumCalls,
            @Value("${resilience.cb.failure-rate-threshold:50}") float failureRateThreshold,
            @Value("${resilience.cb.wait-duration-seconds:5}") long waitSeconds) {

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(slidingWindowSize)
                .minimumNumberOfCalls(minimumCalls)
                .failureRateThreshold(failureRateThreshold)
                .waitDurationInOpenState(Duration.ofSeconds(waitSeconds))
                .permittedNumberOfCallsInHalfOpenState(2)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                // Only infrastructure-style failures count against the breaker.
                .recordExceptions(ResourceAccessException.class, HttpServerErrorException.class)
                .build();
        return CircuitBreakerRegistry.of(config);
    }

    @Bean
    public RetryRegistry retryRegistry(
            @Value("${resilience.retry.max-attempts:3}") int maxAttempts,
            @Value("${resilience.retry.initial-backoff-millis:200}") long initialBackoffMillis,
            @Value("${resilience.retry.backoff-multiplier:2.0}") double multiplier) {

        RetryConfig config = RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(
                        Duration.ofMillis(initialBackoffMillis), multiplier))
                // Retry only transient failures; never retry a 4xx or an open-circuit rejection.
                .retryExceptions(ResourceAccessException.class, HttpServerErrorException.class)
                .build();
        return RetryRegistry.of(config);
    }

    @Bean
    public CircuitBreaker accountServiceCircuitBreaker(CircuitBreakerRegistry registry,
                                                       MeterRegistry meterRegistry) {
        CircuitBreaker cb = registry.circuitBreaker(INSTANCE);
        cb.getEventPublisher().onStateTransition(e ->
                log.warn("Circuit breaker '{}' transitioned {} -> {}", INSTANCE,
                        e.getStateTransition().getFromState(), e.getStateTransition().getToState()));
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
        return cb;
    }

    @Bean
    public Retry accountServiceRetry(RetryRegistry registry, MeterRegistry meterRegistry) {
        Retry retry = registry.retry(INSTANCE);
        TaggedRetryMetrics.ofRetryRegistry(registry).bindTo(meterRegistry);
        return retry;
    }

    @Bean
    public BulkheadRegistry bulkheadRegistry(
            @Value("${resilience.bulkhead.max-concurrent-calls:25}") int maxConcurrentCalls,
            @Value("${resilience.bulkhead.max-wait-millis:0}") long maxWaitMillis) {

        BulkheadConfig config = BulkheadConfig.custom()
                .maxConcurrentCalls(maxConcurrentCalls)
                .maxWaitDuration(Duration.ofMillis(maxWaitMillis))
                .build();
        return BulkheadRegistry.of(config);
    }

    @Bean
    public Bulkhead accountServiceBulkhead(BulkheadRegistry registry, MeterRegistry meterRegistry) {
        Bulkhead bulkhead = registry.bulkhead(INSTANCE);
        TaggedBulkheadMetrics.ofBulkheadRegistry(registry).bindTo(meterRegistry);
        return bulkhead;
    }
}
