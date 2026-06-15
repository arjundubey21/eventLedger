package com.eventledger.gateway.client;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.function.Supplier;

/**
 * Talks to the internal Account Service over REST. Every call is wrapped in the resiliency stack:
 *
 * <ol>
 *   <li><b>Timeout</b> — connect/read timeouts on the underlying request factory (see RestClientConfig).</li>
 *   <li><b>Retry with exponential backoff</b> — transient failures (timeouts, 5xx) are retried a
 *       bounded number of times.</li>
 *   <li><b>Circuit breaker</b> — if the Account Service keeps failing, the breaker opens and calls
 *       fail fast with a 503 instead of piling up.</li>
 * </ol>
 *
 * The trace context (W3C {@code traceparent} header) is propagated automatically because the
 * {@link RestClient} is built from the auto-configured, observation-instrumented builder.
 */
@Component
public class AccountServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AccountServiceClient.class);

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public AccountServiceClient(RestClient accountServiceRestClient,
                                CircuitBreaker accountServiceCircuitBreaker,
                                Retry accountServiceRetry) {
        this.restClient = accountServiceRestClient;
        this.circuitBreaker = accountServiceCircuitBreaker;
        this.retry = accountServiceRetry;
    }

    /** Applies a transaction. Returns normally on success; throws on unavailability. */
    public void applyTransaction(String accountId, AccountTransactionRequest request) {
        execute(() -> restClient.post()
                .uri("/accounts/{accountId}/transactions", accountId)
                .body(request)
                .retrieve()
                .toBodilessEntity());
    }

    /** Fetches the current balance for an account. */
    public AccountBalance getBalance(String accountId) {
        return execute(() -> restClient.get()
                .uri("/accounts/{accountId}/balance", accountId)
                .retrieve()
                .body(AccountBalance.class));
    }

    private <T> T execute(Supplier<T> action) {
        Supplier<T> decorated = Retry.decorateSupplier(retry,
                CircuitBreaker.decorateSupplier(circuitBreaker, action));
        try {
            return decorated.get();
        } catch (CallNotPermittedException e) {
            log.warn("Account Service circuit breaker is OPEN; failing fast");
            throw new AccountServiceUnavailableException(
                    "Account Service is temporarily unavailable (circuit open)", e);
        } catch (ResourceAccessException e) {
            log.warn("Account Service unreachable after retries: {}", e.getMessage());
            throw new AccountServiceUnavailableException("Account Service is unreachable", e);
        } catch (HttpServerErrorException e) {
            log.warn("Account Service returned a server error after retries: {}", e.getStatusCode());
            throw new AccountServiceUnavailableException("Account Service returned an error", e);
        }
    }
}
