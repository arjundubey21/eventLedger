package com.eventledger.gateway.dto;

/**
 * Outcome of processing a {@code POST /events} request, so the controller can choose the status:
 * 201 for a brand-new applied event, 200 for an idempotent duplicate.
 */
public record ProcessResult(EventResponse event, boolean created) {
}
