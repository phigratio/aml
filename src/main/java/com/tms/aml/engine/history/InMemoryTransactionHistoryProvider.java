package com.tms.aml.engine.history;

import com.tms.aml.domain.Transaction;
import com.tms.aml.domain.Transaction.TransactionDirection;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Thread-safe in-memory history cache.
 *
 * Suitable as a baseline implementation and local fallback. For production,
 * replace with a durable implementation backed by a database or event store.
 */
public class InMemoryTransactionHistoryProvider implements TransactionHistoryProvider {

    private final Duration retentionWindow;
    private final int maxTransactionsPerAccount;
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Transaction>> accountTransactions;

    public InMemoryTransactionHistoryProvider(Duration retentionWindow, int maxTransactionsPerAccount) {
        if (retentionWindow == null || retentionWindow.isNegative() || retentionWindow.isZero()) {
            throw new IllegalArgumentException("retentionWindow must be positive");
        }
        if (maxTransactionsPerAccount <= 0) {
            throw new IllegalArgumentException("maxTransactionsPerAccount must be > 0");
        }

        this.retentionWindow = retentionWindow;
        this.maxTransactionsPerAccount = maxTransactionsPerAccount;
        this.accountTransactions = new ConcurrentHashMap<>();
    }

    @Override
    public void recordTransaction(Transaction transaction) {
        Objects.requireNonNull(transaction, "transaction cannot be null");

        ConcurrentLinkedDeque<Transaction> bucket = accountTransactions.computeIfAbsent(
            transaction.accountNumber(), key -> new ConcurrentLinkedDeque<>()
        );

        bucket.addLast(transaction);
        pruneBucket(bucket, LocalDateTime.now().minus(retentionWindow));
    }

    @Override
    public List<Transaction> findTransactions(
        String accountNumber,
        LocalDateTime from,
        LocalDateTime to,
        TransactionDirection direction
    ) {
        if (accountNumber == null || accountNumber.isBlank() || from == null || to == null || from.isAfter(to)) {
            return List.of();
        }

        ConcurrentLinkedDeque<Transaction> bucket = accountTransactions.get(accountNumber);
        if (bucket == null || bucket.isEmpty()) {
            return List.of();
        }

        List<Transaction> results = new ArrayList<>();
        for (Transaction transaction : bucket) {
            LocalDateTime txDate = transaction.transactionDate();
            if (txDate.isBefore(from) || txDate.isAfter(to)) {
                continue;
            }
            if (direction != null && transaction.direction() != direction) {
                continue;
            }
            results.add(transaction);
        }

        results.sort(Comparator.comparing(Transaction::transactionDate));
        return List.copyOf(results);
    }

    @Override
    public Optional<Transaction> findLatestTransaction(String accountNumber, LocalDateTime atOrBefore) {
        return findLatestTransaction(accountNumber, atOrBefore, null);
    }

    @Override
    public Optional<Transaction> findLatestTransaction(
        String accountNumber,
        LocalDateTime atOrBefore,
        TransactionDirection direction
    ) {
        if (accountNumber == null || accountNumber.isBlank() || atOrBefore == null) {
            return Optional.empty();
        }

        ConcurrentLinkedDeque<Transaction> bucket = accountTransactions.get(accountNumber);
        if (bucket == null || bucket.isEmpty()) {
            return Optional.empty();
        }

        Transaction latest = null;
        for (Transaction transaction : bucket) {
            if (transaction.transactionDate().isAfter(atOrBefore)) {
                continue;
            }
            if (direction != null && transaction.direction() != direction) {
                continue;
            }
            if (latest == null || transaction.transactionDate().isAfter(latest.transactionDate())) {
                latest = transaction;
            }
        }

        return Optional.ofNullable(latest);
    }

    private void pruneBucket(ConcurrentLinkedDeque<Transaction> bucket, LocalDateTime cutoffTime) {
        while (bucket.size() > maxTransactionsPerAccount) {
            bucket.pollFirst();
        }

        while (true) {
            Transaction head = bucket.peekFirst();
            if (head == null || !head.transactionDate().isBefore(cutoffTime)) {
                break;
            }
            bucket.pollFirst();
        }
    }
}
