package com.eventledger.account;

import com.eventledger.account.dto.BalanceResponse;
import com.eventledger.account.dto.TransactionRequest;
import com.eventledger.account.model.TransactionType;
import com.eventledger.account.repository.TransactionRepository;
import com.eventledger.account.service.AccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves idempotency holds under concurrency: many simultaneous submissions of the same eventId
 * must result in exactly one stored transaction and a correct, non-duplicated balance.
 */
@SpringBootTest
class AccountConcurrencyTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransactionRepository repository;

    @Test
    void concurrentDuplicateSubmissionsApplyExactlyOnce() throws InterruptedException {
        repository.deleteAll();
        int threads = 12;
        var request = new TransactionRequest("evt-concurrent", TransactionType.CREDIT,
                new BigDecimal("100.00"), "USD", Instant.parse("2026-05-15T10:00:00Z"));

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    accountService.applyTransaction("acct-concurrent", request);
                } catch (Exception ignored) {
                    // Losing threads resolve to the existing record; any transient error is fine here.
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();                 // release all threads at once
        done.await(10, TimeUnit.SECONDS);
        pool.shutdownNow();

        BalanceResponse balance = accountService.getBalance("acct-concurrent");
        assertThat(balance.transactionCount()).isEqualTo(1L);
        assertThat(balance.balance()).isEqualByComparingTo("100.00");
    }
}
