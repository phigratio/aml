package com.tms.aml.engine.history;

import com.tms.aml.domain.Transaction;
import com.tms.aml.domain.Transaction.TransactionDirection;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Abstraction for retrieving and storing historical transactions used by AML rules.
 * Implementations must be thread-safe because rules execute concurrently on virtual threads.
 */
public interface TransactionHistoryProvider {

    /**
     * Persist a transaction for future window-based evaluations.
     */
    void recordTransaction(Transaction transaction);

    /**
     * Fetch transactions for an account and optional direction within [from, to].
     */
    List<Transaction> findTransactions(
        String accountNumber,
        LocalDateTime from,
        LocalDateTime to,
        TransactionDirection direction
    );

    /**
     * Convenience helper for inbound-only lookups.
     */
    default List<Transaction> findInboundTransactions(
        String accountNumber,
        LocalDateTime from,
        LocalDateTime to
    ) {
        return findTransactions(accountNumber, from, to, TransactionDirection.CREDIT);
    }

    /**
     * Fetch the most recent transaction for an account up to (and including) the given timestamp.
     */
    Optional<Transaction> findLatestTransaction(String accountNumber, LocalDateTime atOrBefore);

    /**
     * Fetch the most recent transaction for an account and direction up to (and including) the given timestamp.
     */
    Optional<Transaction> findLatestTransaction(
        String accountNumber,
        LocalDateTime atOrBefore,
        TransactionDirection direction
    );
}
