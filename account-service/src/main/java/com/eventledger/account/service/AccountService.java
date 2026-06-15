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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

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
     * Applies a transaction idempotently and safely under concurrency.
     *
     * <p>If a transaction with the same {@code eventId} already exists, the existing record is
     * returned unchanged and the balance is not affected (a payload mismatch yields a 409). The
     * insert is guarded by the {@code eventId} primary key: if two concurrent requests race past the
     * existence check, the loser catches the {@link DataIntegrityViolationException} and resolves to
     * the winner's record instead of failing with a 500. An account is single-currency.
     *
     * <p>Not wrapped in a single {@code @Transactional} method on purpose: that lets the failed
     * insert roll back in isolation so the subsequent re-read can still run.
     */
    public ApplyResult applyTransaction(String accountId, TransactionRequest request) {
        Optional<Transaction> existing = repository.findById(request.eventId());
        if (existing.isPresent()) {
            return duplicate(existing.get(), accountId, request);
        }

        repository.findFirstByAccountId(accountId).ifPresent(t -> {
            if (!t.getCurrency().equals(request.currency())) {
                throw new ConflictException("Account " + accountId + " is denominated in "
                        + t.getCurrency() + "; cannot apply a " + request.currency() + " transaction");
            }
        });

        try {
            Transaction saved = repository.saveAndFlush(new Transaction(
                    request.eventId(), accountId, request.type(), request.amount(),
                    request.currency(), request.eventTimestamp(), clock.instant()));
            log.info("Transaction applied: eventId={} accountId={} type={} amount={}",
                    saved.getEventId(), accountId, saved.getType(), saved.getAmount());
            countApplied(request.type(), "applied");
            return new ApplyResult(TransactionResponse.from(saved), true);
        } catch (DataIntegrityViolationException raced) {
            // Concurrent insert of the same eventId won; treat this call as a duplicate.
            Transaction now = repository.findById(request.eventId()).orElseThrow(() -> raced);
            return duplicate(now, accountId, request);
        }
    }

    private ApplyResult duplicate(Transaction existing, String accountId, TransactionRequest request) {
        assertSamePayload(existing, request);
        log.info("Duplicate transaction ignored: eventId={} accountId={}", existing.getEventId(), accountId);
        countApplied(request.type(), "duplicate");
        return new ApplyResult(TransactionResponse.from(existing), false);
    }

    private void assertSamePayload(Transaction existing, TransactionRequest request) {
        boolean same = existing.getType() == request.type()
                && existing.getAmount().compareTo(request.amount()) == 0
                && existing.getCurrency().equals(request.currency());
        if (!same) {
            throw new ConflictException("eventId " + existing.getEventId()
                    + " already exists with a different payload");
        }
    }

    @Transactional(readOnly = true)
    public BalanceResponse getBalance(String accountId) {
        BigDecimal credits = repository.sumAmountByType(accountId, TransactionType.CREDIT);
        BigDecimal debits = repository.sumAmountByType(accountId, TransactionType.DEBIT);
        long count = repository.countByAccountId(accountId);
        BigDecimal balance = credits.subtract(debits);
        String currency = repository.findFirstByAccountId(accountId)
                .map(Transaction::getCurrency).orElse(null);
        log.info("Balance computed: accountId={} currency={} balance={} credits={} debits={}",
                accountId, currency, balance, credits, debits);
        return new BalanceResponse(accountId, currency, balance, credits, debits, count);
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
