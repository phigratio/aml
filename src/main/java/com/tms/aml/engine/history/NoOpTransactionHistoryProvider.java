package com.tms.aml.engine.history;

import com.tms.aml.domain.Transaction;
import com.tms.aml.domain.Transaction.TransactionDirection;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Safe fallback provider that stores nothing.
 */
public class NoOpTransactionHistoryProvider implements TransactionHistoryProvider {

    @Override
    public void recordTransaction(Transaction transaction) {
        // Intentionally empty.
    }

    @Override
    public List<Transaction> findTransactions(
        String accountNumber,
        LocalDateTime from,
        LocalDateTime to,
        TransactionDirection direction
    ) {
        return List.of();
    }

    @Override
    public Optional<Transaction> findLatestTransaction(String accountNumber, LocalDateTime atOrBefore) {
        return Optional.empty();
    }

    @Override
    public Optional<Transaction> findLatestTransaction(
        String accountNumber,
        LocalDateTime atOrBefore,
        TransactionDirection direction
    ) {
        return Optional.empty();
    }
}
