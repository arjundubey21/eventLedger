package com.eventledger.account.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A transaction that has been applied to an account.
 *
 * <p>The {@code eventId} is the primary key. Because the upstream system may deliver the same
 * event more than once, the Account Service uses the event id as the natural idempotency key:
 * applying the same event twice is a no-op.
 */
@Entity
@Table(name = "transactions", indexes = {
        @Index(name = "idx_tx_account", columnList = "accountId"),
        @Index(name = "idx_tx_account_ts", columnList = "accountId, eventTimestamp")
})
public class Transaction {

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

    /** When the event originally occurred upstream (used for chronological ordering). */
    @Column(nullable = false)
    private Instant eventTimestamp;

    /** When this service persisted the transaction. */
    @Column(nullable = false)
    private Instant appliedAt;

    protected Transaction() {
        // Required by JPA
    }

    public Transaction(String eventId, String accountId, TransactionType type, BigDecimal amount,
                       String currency, Instant eventTimestamp, Instant appliedAt) {
        this.eventId = eventId;
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.currency = currency;
        this.eventTimestamp = eventTimestamp;
        this.appliedAt = appliedAt;
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

    public Instant getAppliedAt() {
        return appliedAt;
    }
}
