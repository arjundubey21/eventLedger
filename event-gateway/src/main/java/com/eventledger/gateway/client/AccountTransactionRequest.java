package com.eventledger.gateway.client;

import com.eventledger.gateway.model.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Payload the Gateway sends to {@code POST /accounts/{id}/transactions} on the Account Service.
 * This is the explicit contract between the two services.
 */
public record AccountTransactionRequest(
        String eventId,
        TransactionType type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp
) {
}
