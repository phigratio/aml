package com.tms.aml.engine.history;

import com.tms.aml.domain.Transaction;
import com.tms.aml.domain.Transaction.TransactionDirection;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL-backed transaction history provider.
 */
public class PostgresTransactionHistoryProvider implements TransactionHistoryProvider {

    private final JdbcTemplate jdbcTemplate;
    private final String tableName;

    public PostgresTransactionHistoryProvider(JdbcTemplate jdbcTemplate, String tableName) {
        if (jdbcTemplate == null) {
            throw new IllegalArgumentException("jdbcTemplate cannot be null");
        }
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("tableName cannot be null or blank");
        }
        if (!tableName.matches("[a-zA-Z0-9_\\.]+")) {
            throw new IllegalArgumentException("tableName contains invalid characters");
        }

        this.jdbcTemplate = jdbcTemplate;
        this.tableName = tableName;
    }

    @Override
    public void recordTransaction(Transaction transaction) {
        String sql = """
            INSERT INTO %s (
                transaction_id,
                customer_id,
                account_number,
                amount,
                currency,
                direction,
                transaction_date,
                counterparty_name,
                counterparty_account,
                transaction_purpose,
                risk_score
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (transaction_id) DO NOTHING
            """.formatted(tableName);

        jdbcTemplate.update(
            sql,
            transaction.transactionId(),
            transaction.customerId(),
            transaction.accountNumber(),
            transaction.amount(),
            transaction.currency(),
            transaction.direction().name(),
            Timestamp.valueOf(transaction.transactionDate()),
            transaction.counterpartyName(),
            transaction.counterpartyAccount(),
            transaction.transactionPurpose(),
            transaction.riskScore()
        );
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

        if (direction == null) {
            String sql = """
                SELECT transaction_id, customer_id, account_number, amount, currency, direction,
                       transaction_date, counterparty_name, counterparty_account, transaction_purpose, risk_score
                FROM %s
                WHERE account_number = ?
                  AND transaction_date BETWEEN ? AND ?
                ORDER BY transaction_date ASC
                """.formatted(tableName);

            return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> mapTransaction(rs),
                accountNumber,
                Timestamp.valueOf(from),
                Timestamp.valueOf(to)
            );
        }

        String sql = """
            SELECT transaction_id, customer_id, account_number, amount, currency, direction,
                   transaction_date, counterparty_name, counterparty_account, transaction_purpose, risk_score
            FROM %s
            WHERE account_number = ?
              AND direction = ?
              AND transaction_date BETWEEN ? AND ?
            ORDER BY transaction_date ASC
            """.formatted(tableName);

        return jdbcTemplate.query(
            sql,
            (rs, rowNum) -> mapTransaction(rs),
            accountNumber,
            direction.name(),
            Timestamp.valueOf(from),
            Timestamp.valueOf(to)
        );
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

        List<Transaction> rows;
        if (direction == null) {
            String sql = """
                SELECT transaction_id, customer_id, account_number, amount, currency, direction,
                       transaction_date, counterparty_name, counterparty_account, transaction_purpose, risk_score
                FROM %s
                WHERE account_number = ?
                  AND transaction_date <= ?
                ORDER BY transaction_date DESC
                LIMIT 1
                """.formatted(tableName);

            rows = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> mapTransaction(rs),
                accountNumber,
                Timestamp.valueOf(atOrBefore)
            );
        } else {
            String sql = """
                SELECT transaction_id, customer_id, account_number, amount, currency, direction,
                       transaction_date, counterparty_name, counterparty_account, transaction_purpose, risk_score
                FROM %s
                WHERE account_number = ?
                  AND direction = ?
                  AND transaction_date <= ?
                ORDER BY transaction_date DESC
                LIMIT 1
                """.formatted(tableName);

            rows = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> mapTransaction(rs),
                accountNumber,
                direction.name(),
                Timestamp.valueOf(atOrBefore)
            );
        }

        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(rows.get(0));
    }

    private Transaction mapTransaction(ResultSet rs) throws SQLException {
        String directionRaw = rs.getString("direction");
        TransactionDirection direction = TransactionDirection.valueOf(directionRaw);

        return new Transaction(
            rs.getString("transaction_id"),
            rs.getString("customer_id"),
            rs.getString("account_number"),
            rs.getBigDecimal("amount"),
            rs.getString("currency"),
            direction,
            rs.getTimestamp("transaction_date").toLocalDateTime(),
            rs.getString("counterparty_name"),
            rs.getString("counterparty_account"),
            rs.getString("transaction_purpose"),
            rs.getObject("risk_score") != null ? rs.getBigDecimal("risk_score").doubleValue() : null
        );
    }
}
