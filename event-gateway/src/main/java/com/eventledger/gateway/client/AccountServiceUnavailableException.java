package com.eventledger.gateway.client;

/**
 * Thrown when the Account Service cannot be reached after retries, or when the circuit breaker is
 * open. The exception handler maps this to {@code 503 Service Unavailable}.
 */
public class AccountServiceUnavailableException extends RuntimeException {

    public AccountServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public AccountServiceUnavailableException(String message) {
        super(message);
    }
}
