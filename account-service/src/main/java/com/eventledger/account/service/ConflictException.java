package com.eventledger.account.service;

/**
 * Signals a request that conflicts with existing state — either the same {@code eventId} replayed
 * with a different payload, or a transaction in a currency that differs from the account's
 * established currency. Mapped to {@code 409 Conflict}.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
