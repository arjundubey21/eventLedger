package com.eventledger.account.service;

import com.eventledger.account.dto.AccountDetailsResponse;
import com.eventledger.account.dto.ApplyResult;
import com.eventledger.account.dto.BalanceResponse;
import com.eventledger.account.dto.TransactionRequest;
import com.eventledger.account.dto.TransactionResponse;
import com.eventledger.account.model.Transaction;
import com.eventledger.account.model.TransactionType;
import com.eventledger.account.repository.TransactionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final TransactionRepository repository;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public AccountService(TransactionRepository repository, MeterRegistry meterRegistry, Clock clock) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /**
     * Applies a transaction idempotently. If a transaction with the same {@code eventId} already
     * exists, the existing record is returned unchanged and the balance is not affected.
     */
    @Transactional
    public ApplyResult applyTransaction(String accountId, TransactionRequest request) {
        return repository.findById(request.eventId())
                .map(existing -> {
                    log.info("Duplicate transaction ignored: eventId={} accountId={}",
                            existing.getEventId(), accountId);
                    countApplied(request.type(), "duplicate");
                    return new ApplyResult(TransactionResponse.from(existing), false);
                })
                .orElseGet(() -> {
                    Transaction saved = repository.save(new Transaction(
                            request.eventId(), accountId, request.type(), request.amount(),
                            request.currency(), request.eventTimestamp(), clock.instant()));
                    log.info("Transaction applied: eventId={} accountId={} type={} amount={}",
                            saved.getEventId(), accountId, saved.getType(), saved.getAmount());
                    countApplied(request.type(), "applied");
                    return new ApplyResult(TransactionResponse.from(saved), true);
                });
    }

    @Transactional(readOnly = true)
    public BalanceResponse getBalance(String accountId) {
        BigDecimal credits = repository.sumAmountByType(accountId, TransactionType.CREDIT);
        BigDecimal debits = repository.sumAmountByType(accountId, TransactionType.DEBIT);
        long count = repository.countByAccountId(accountId);
        BigDecimal balance = credits.subtract(debits);
        log.info("Balance computed: accountId={} balance={} credits={} debits={}",
                accountId, balance, credits, debits);
        return new BalanceResponse(accountId, balance, credits, debits, count);
    }

    @Transactional(readOnly = true)
    public AccountDetailsResponse getAccountDetails(String accountId) {
        List<Transaction> transactions = repository.findByAccountIdOrderByEventTimestampAsc(accountId);
        BigDecimal credits = repository.sumAmountByType(accountId, TransactionType.CREDIT);
        BigDecimal debits = repository.sumAmountByType(accountId, TransactionType.DEBIT);
        List<TransactionResponse> history = transactions.stream()
                .map(TransactionResponse::from)
                .toList();
        return new AccountDetailsResponse(accountId, credits.subtract(debits), history.size(), history);
    }

    private void countApplied(TransactionType type, String outcome) {
        meterRegistry.counter("account.transactions.applied",
                "type", type.name(), "outcome", outcome).increment();
    }
}
