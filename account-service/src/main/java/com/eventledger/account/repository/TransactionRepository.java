package com.eventledger.account.repository;

import com.eventledger.account.model.Transaction;
import com.eventledger.account.model.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, String> {

    List<Transaction> findByAccountIdOrderByEventTimestampAsc(String accountId);

    /**
     * Sum of amounts for an account filtered by transaction type. Returns {@code null} when no rows
     * match, which the service treats as zero. Computing the balance from an aggregate query means
     * the result is correct regardless of the order in which events arrived.
     */
    @Query("select coalesce(sum(t.amount), 0) from Transaction t " +
           "where t.accountId = :accountId and t.type = :type")
    BigDecimal sumAmountByType(@Param("accountId") String accountId, @Param("type") TransactionType type);

    long countByAccountId(String accountId);
}
