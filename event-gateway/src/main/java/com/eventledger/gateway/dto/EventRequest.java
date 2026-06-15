package com.eventledger.gateway.dto;

import com.eventledger.gateway.model.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Incoming payload for {@code POST /events}.
 *
 * <p>Validation rules enforce the contract: required fields must be present, the amount must be
 * strictly positive, and {@code type} must deserialize to a known {@link TransactionType}
 * (an unknown value produces a 400 via the exception handler).
 */
public record EventRequest(
        @NotBlank(message = "eventId is required") String eventId,
        @NotBlank(message = "accountId is required") String accountId,
        @NotNull(message = "type is required and must be CREDIT or DEBIT") TransactionType type,
        @NotNull(message = "amount is required") @Positive(message = "amount must be greater than 0") BigDecimal amount,
        @NotBlank(message = "currency is required") String currency,
        @NotNull(message = "eventTimestamp is required") Instant eventTimestamp,
        Map<String, Object> metadata
) {
}
