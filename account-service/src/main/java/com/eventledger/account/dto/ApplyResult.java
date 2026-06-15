package com.eventledger.account.dto;

/**
 * Wraps a transaction response together with whether it was newly applied or already existed.
 * Lets the controller pick the right HTTP status (201 vs 200) for idempotent replays.
 */
public record ApplyResult(TransactionResponse transaction, boolean created) {
}
