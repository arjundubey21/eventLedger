package com.eventledger.gateway.client;

import java.math.BigDecimal;

/** Balance payload returned by the Account Service and proxied back to the client. */
public record AccountBalance(
        String accountId,
        String currency,
        BigDecimal balance,
        BigDecimal totalCredits,
        BigDecimal totalDebits,
        long transactionCount
) {
}
