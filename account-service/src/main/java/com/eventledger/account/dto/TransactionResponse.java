package com.eventledger.account.dto;

import com.eventledger.account.model.Transaction;
import com.eventledger.account.model.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionResponse(
        String eventId,
        String accountId,
        TransactionType type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp,
        Instant appliedAt
) {
    public static TransactionResponse from(Transaction t) {
        return new TransactionResponse(t.getEventId(), t.getAccountId(), t.getType(), t.getAmount(),
                t.getCurrency(), t.getEventTimestamp(), t.getAppliedAt());
    }
}
