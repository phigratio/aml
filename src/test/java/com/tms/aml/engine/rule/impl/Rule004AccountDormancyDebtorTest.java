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

class Rule004AccountDormancyDebtorTest {

    @Test
    void shouldTriggerForDormantOutgoingWithHighAmount() throws Exception {
        InMemoryTransactionHistoryProvider history =
            new InMemoryTransactionHistoryProvider(Duration.ofDays(365), 10_000);

        Rule004AccountDormancyDebtor rule = new Rule004AccountDormancyDebtor();
        rule.setDormancyThresholdDays(90);
        rule.setAlertThreshold(new BigDecimal("1000.00"));
        rule.setApplyRiskMultipliers(false);

        CustomerContext customer = customer("CUST_D1", "ACC_D1", CustomerContext.RiskRating.MEDIUM);
        LocalDateTime now = LocalDateTime.now();

        history.recordTransaction(tx("TX_PREV_DEBIT", "ACC_D1", Transaction.TransactionDirection.DEBIT,
            new BigDecimal("100.00"), now.minusDays(120)));

        RuleResult result = rule.evaluate(
            tx("TX_NEW_DEBIT", "ACC_D1", Transaction.TransactionDirection.DEBIT,
                new BigDecimal("4000.00"), now),
            customer,
            new RuleContext(history, Instant.now(), Map.of())
        );

        assertTrue(result.triggered());
        assertTrue((Long) result.evidence().get("inactivity_period_days") > 90);
        assertTrue(result.severityScore() >= 0.5);
    }

    @Test
    void shouldNotTriggerForCreditTransaction() throws Exception {
        InMemoryTransactionHistoryProvider history =
            new InMemoryTransactionHistoryProvider(Duration.ofDays(365), 10_000);

        Rule004AccountDormancyDebtor rule = new Rule004AccountDormancyDebtor();
        rule.setDormancyThresholdDays(90);
        rule.setAlertThreshold(new BigDecimal("1000.00"));

        CustomerContext customer = customer("CUST_D2", "ACC_D2", CustomerContext.RiskRating.HIGH);

        RuleResult result = rule.evaluate(
            tx("TX_CREDIT", "ACC_D2", Transaction.TransactionDirection.CREDIT,
                new BigDecimal("7000.00"), LocalDateTime.now()),
            customer,
            new RuleContext(history, Instant.now(), Map.of())
        );

        assertFalse(result.triggered());
        assertEquals(0.0, result.severityScore());
    }

    private static Transaction tx(
        String txId,
        String account,
        Transaction.TransactionDirection direction,
        BigDecimal amount,
        LocalDateTime txTime
    ) {
        return new Transaction(
            txId,
            "CUST",
            account,
            amount,
            "USD",
            direction,
            txTime,
            "Sender",
            "SENDER_ACC",
            "test",
            null
        );
    }

    private static CustomerContext customer(
        String customerId,
        String account,
        CustomerContext.RiskRating riskRating
    ) {
        return new CustomerContext(
            customerId,
            account,
            CustomerContext.CustomerType.INDIVIDUAL,
            riskRating,
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
