package com.eventledger.gateway.service;

/**
 * Thrown when the same {@code eventId} is submitted again with a different payload. Mapped to
 * {@code 409 Conflict} so a client can't silently overwrite a previously recorded event.
 */
public class EventConflictException extends RuntimeException {

    public EventConflictException(String message) {
        super(message);
    }
}
