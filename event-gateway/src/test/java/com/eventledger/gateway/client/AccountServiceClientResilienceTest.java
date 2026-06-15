package com.eventledger.gateway.client;

import com.eventledger.gateway.model.TransactionType;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Exercises the resiliency stack (circuit breaker + retry) by simulating Account Service failures
 * with {@link MockRestServiceServer}.
 */
class AccountServiceClientResilienceTest {

    private static final AccountTransactionRequest TX = new AccountTransactionRequest(
            "evt-1", TransactionType.CREDIT, new BigDecimal("10.00"), "USD", Instant.parse("2026-05-15T10:00:00Z"));

    private CircuitBreaker circuitBreaker(int windowSize) {
        return CircuitBreaker.of("test", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(windowSize)
                .minimumNumberOfCalls(windowSize)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .recordExceptions(ResourceAccessException.class, HttpServerErrorException.class)
                .build());
    }

    @Test
    void circuitBreakerOpensAfterRepeatedFailuresAndThenFailsFast() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        RestClient restClient = builder.baseUrl("http://account-service").build();
        server.expect(ExpectedCount.manyTimes(), requestTo(containsString("/transactions")))
                .andRespond(withServerError());

        // Retry disabled so each call counts once against the breaker.
        Retry noRetry = Retry.of("test", RetryConfig.custom().maxAttempts(1).build());
        CircuitBreaker cb = circuitBreaker(3);
        AccountServiceClient client = new AccountServiceClient(restClient, cb, noRetry);

        // First 3 calls hit the (failing) service and trip the breaker.
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> client.applyTransaction("acct-cb", TX))
                    .isInstanceOf(AccountServiceUnavailableException.class);
        }
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Once OPEN, calls fail fast with a circuit-open cause (no downstream call made).
        assertThatThrownBy(() -> client.applyTransaction("acct-cb", TX))
                .isInstanceOf(AccountServiceUnavailableException.class)
                .hasMessageContaining("circuit open");
    }

    @Test
    void retryRecoversFromTransientFailures() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.baseUrl("http://account-service").build();

        // Fail twice, then succeed; retry (max 3 attempts) should ride through.
        server.expect(ExpectedCount.times(2), requestTo(containsString("/transactions")))
                .andRespond(withServerError());
        server.expect(ExpectedCount.times(1), requestTo(containsString("/transactions")))
                .andRespond(withSuccess());

        Retry retry = Retry.of("test", RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(10))
                .retryExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                .build());
        AccountServiceClient client = new AccountServiceClient(restClient, circuitBreaker(10), retry);

        // Should not throw: the third attempt succeeds.
        client.applyTransaction("acct-retry", TX);
        server.verify();
    }
}
