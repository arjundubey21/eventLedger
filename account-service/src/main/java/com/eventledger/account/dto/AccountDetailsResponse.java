package com.eventledger.account.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Account details: current balance plus the transaction history in chronological order
 * (by event timestamp), so out-of-order arrivals are presented correctly.
 */
public record AccountDetailsResponse(
        String accountId,
        BigDecimal balance,
        long transactionCount,
        List<TransactionResponse> transactions
) {
}
