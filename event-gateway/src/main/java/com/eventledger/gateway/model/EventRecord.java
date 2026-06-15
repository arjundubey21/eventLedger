package com.eventledger.gateway.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * The Gateway's local record of a received event. The {@code eventId} is the primary key, which is
 * what enforces idempotency: a second insert with the same id is rejected / short-circuited.
 */
@Entity
@Table(name = "events", indexes = {
        @Index(name = "idx_event_account_ts", columnList = "accountId, eventTimestamp")
})
public class EventRecord {

    @Id
    @Column(nullable = false, updatable = false)
    private String eventId;

    @Column(nullable = false)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TransactionType type;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 8)
    private String currency;

    @Column(nullable = false)
    private Instant eventTimestamp;

    @Convert(converter = MetadataConverter.class)
    @Column(columnDefinition = "CLOB")
    private Map<String, Object> metadata;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EventStatus status;

    @Column(nullable = false)
    private Instant receivedAt;

    protected EventRecord() {
        // Required by JPA
    }

    public EventRecord(String eventId, String accountId, TransactionType type, BigDecimal amount,
                       String currency, Instant eventTimestamp, Map<String, Object> metadata,
                       EventStatus status, Instant receivedAt) {
        this.eventId = eventId;
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.currency = currency;
        this.eventTimestamp = eventTimestamp;
        this.metadata = metadata;
        this.status = status;
        this.receivedAt = receivedAt;
    }

    public String getEventId() {
        return eventId;
    }

    public String getAccountId() {
        return accountId;
    }

    public TransactionType getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getEventTimestamp() {
        return eventTimestamp;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public EventStatus getStatus() {
        return status;
    }

    public void setStatus(EventStatus status) {
        this.status = status;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
