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

class Rule003AccountDormancyCreditorTest {

    @Test
    void shouldTriggerForDormantAccountWithLargeCredit() throws Exception {
        InMemoryTransactionHistoryProvider history =
            new InMemoryTransactionHistoryProvider(Duration.ofDays(365), 10_000);

        Rule003AccountDormancyCreditor rule = new Rule003AccountDormancyCreditor();
        rule.setDormancyThresholdDays(90);
        rule.setAmountThreshold(new BigDecimal("1000.00"));

        CustomerContext customer = customer("CUST_DORM", "ACC_DORM");
        LocalDateTime now = LocalDateTime.now();

        history.recordTransaction(tx("TX_OLD", "ACC_DORM", new BigDecimal("100.00"), now.minusDays(120)));

        RuleResult result = rule.evaluate(
            tx("TX_NEW", "ACC_DORM", new BigDecimal("5000.00"), now),
            customer,
            new RuleContext(history, Instant.now(), Map.of())
        );

        assertTrue(result.triggered());
        assertTrue((Long) result.evidence().get("inactivity_period_days") > 90);
        assertTrue(result.severityScore() >= 0.5);
    }

    @Test
    void shouldNotTriggerWhenInactivityBelowThreshold() throws Exception {
        InMemoryTransactionHistoryProvider history =
            new InMemoryTransactionHistoryProvider(Duration.ofDays(365), 10_000);

        Rule003AccountDormancyCreditor rule = new Rule003AccountDormancyCreditor();
        rule.setDormancyThresholdDays(90);
        rule.setAmountThreshold(new BigDecimal("1000.00"));

        CustomerContext customer = customer("CUST_ACTIVE", "ACC_ACTIVE");
        LocalDateTime now = LocalDateTime.now();

        history.recordTransaction(tx("TX_RECENT", "ACC_ACTIVE", new BigDecimal("100.00"), now.minusDays(10)));

        RuleResult result = rule.evaluate(
            tx("TX_NEW", "ACC_ACTIVE", new BigDecimal("5000.00"), now),
            customer,
            new RuleContext(history, Instant.now(), Map.of())
        );

        assertFalse(result.triggered());
        assertEquals(0.0, result.severityScore());
    }

    private static Transaction tx(String txId, String account, BigDecimal amount, LocalDateTime txTime) {
        return new Transaction(
            txId,
            "CUST",
            account,
            amount,
            "USD",
            Transaction.TransactionDirection.CREDIT,
            txTime,
            "Sender",
            "SENDER_ACC",
            "test",
            null
        );
    }

    private static CustomerContext customer(String customerId, String account) {
        return new CustomerContext(
            customerId,
            account,
            CustomerContext.CustomerType.INDIVIDUAL,
            CustomerContext.RiskRating.MEDIUM,
            LocalDate.now().minusYears(2),
            LocalDate.now().minusYears(2),
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
