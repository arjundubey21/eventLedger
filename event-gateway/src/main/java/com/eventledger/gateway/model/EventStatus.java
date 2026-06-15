package com.eventledger.gateway.model;

/**
 * Lifecycle of an event record at the Gateway.
 *
 * <ul>
 *   <li>{@code PENDING} — the event is durably stored locally but the Account Service has not yet
 *       confirmed the transaction was applied (e.g. the downstream service was unavailable).</li>
 *   <li>{@code APPLIED} — the Account Service has confirmed the transaction.</li>
 * </ul>
 *
 * Storing the event before the downstream call is what lets {@code GET /events} keep working even
 * when the Account Service is down, and lets a retried {@code POST} finish applying a pending event.
 */
public enum EventStatus {
    PENDING,
    APPLIED
}
