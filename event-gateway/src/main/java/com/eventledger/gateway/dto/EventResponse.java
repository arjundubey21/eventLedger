package com.eventledger.gateway.dto;

import com.eventledger.gateway.model.EventRecord;
import com.eventledger.gateway.model.EventStatus;
import com.eventledger.gateway.model.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record EventResponse(
        String eventId,
        String accountId,
        TransactionType type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp,
        Map<String, Object> metadata,
        EventStatus status,
        Instant receivedAt
) {
    public static EventResponse from(EventRecord e) {
        return new EventResponse(e.getEventId(), e.getAccountId(), e.getType(), e.getAmount(),
                e.getCurrency(), e.getEventTimestamp(), e.getMetadata(), e.getStatus(), e.getReceivedAt());
    }
}
