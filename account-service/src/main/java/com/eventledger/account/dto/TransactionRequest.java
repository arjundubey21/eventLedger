package com.eventledger.account.dto;

import com.eventledger.account.model.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Request body for applying a transaction. Sent by the Event Gateway.
 */
public record TransactionRequest(
        @NotBlank String eventId,
        @NotNull TransactionType type,
        @NotNull @Positive BigDecimal amount,
        @NotBlank String currency,
        @NotNull Instant eventTimestamp
) {
}
