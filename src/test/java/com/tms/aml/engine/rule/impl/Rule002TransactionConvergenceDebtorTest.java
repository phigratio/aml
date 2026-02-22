package com.tms.aml.engine.rule.impl;

import com.tms.aml.domain.CustomerContext;
import com.tms.aml.domain.RuleResult;
import com.tms.aml.domain.Transaction;
import com.tms.aml.engine.history.InMemoryTransactionHistoryProvider;
import com.tms.aml.engine.rule.RuleContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Rule002TransactionConvergenceDebtorTest {

    @Test
    void shouldTriggerWhenUniqueSenderThresholdExceeded() {
        InMemoryTransactionHistoryProvider history =
            new InMemoryTransactionHistoryProvider(Duration.ofHours(48), 10_000);

        Rule002TransactionConvergenceDebtor rule = new Rule002TransactionConvergenceDebtor();
        rule.setWindowHours(24);
        rule.setUniqueSenderThreshold(2);

        CustomerContext customer = customer("CUST_A", "ACC_MAIN");
        LocalDateTime now = LocalDateTime.now();

        history.recordTransaction(tx("TX_H1", "ACC_MAIN", "SENDER_1", now.minusHours(2)));
        history.recordTransaction(tx("TX_H2", "ACC_MAIN", "SENDER_2", now.minusHours(1)));

        RuleResult result = rule.evaluate(
            tx("TX_CURR", "ACC_MAIN", "SENDER_3", now),
            customer,
            new RuleContext(history, Instant.now(), Map.of())
        );

        assertTrue(result.triggered());
        assertEquals(3, result.evidence().get("unique_sender_count"));
        assertEquals(24, result.evidence().get("time_window_hours"));
        assertTrue(result.severityScore() >= 0.5);
    }

    @Test
    void shouldNotTriggerWhenBelowThreshold() {
        InMemoryTransactionHistoryProvider history =
            new InMemoryTransactionHistoryProvider(Duration.ofHours(48), 10_000);

        Rule002TransactionConvergenceDebtor rule = new Rule002TransactionConvergenceDebtor();
        rule.setWindowHours(24);
        rule.setUniqueSenderThreshold(3);

        CustomerContext customer = customer("CUST_B", "ACC_MAIN_2");
        LocalDateTime now = LocalDateTime.now();

        history.recordTransaction(tx("TX_H1", "ACC_MAIN_2", "SENDER_1", now.minusHours(2)));

        RuleResult result = rule.evaluate(
            tx("TX_CURR", "ACC_MAIN_2", "SENDER_2", now),
            customer,
            new RuleContext(history, Instant.now(), Map.of())
        );

        assertFalse(result.triggered());
        assertEquals(2, result.evidence().get("unique_sender_count"));
        assertEquals(0.0, result.severityScore());
    }

    private static Transaction tx(String txId, String account, String senderAccount, LocalDateTime txTime) {
        return new Transaction(
            txId,
            "CREDITOR_CUST",
            account,
            new BigDecimal("1000.00"),
            "USD",
            Transaction.TransactionDirection.CREDIT,
            txTime,
            "Sender",
            senderAccount,
            "Test payment",
            null
        );
    }

    private static CustomerContext customer(String customerId, String account) {
        return new CustomerContext(
            customerId,
            account,
            CustomerContext.CustomerType.INDIVIDUAL,
            CustomerContext.RiskRating.MEDIUM,
            LocalDate.now().minusYears(1),
            LocalDate.now().minusYears(1),
            "TZ",
            false,
            false,
            new BigDecimal("1000.00"),
            new BigDecimal("500.00"),
            new BigDecimal("2000.00"),
            10,
            null,
            Set.of("USD"),
            Map.of()
        );
    }
}
