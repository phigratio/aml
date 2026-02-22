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

import static org.junit.jupiter.api.Assertions.assertTrue;

class Rule028AgeClassificationDebtorTest {

    @Test
    void shouldTriggerForNewAccountWithHighDebitVelocity() {
        InMemoryTransactionHistoryProvider history = new InMemoryTransactionHistoryProvider(Duration.ofDays(30), 10_000);
        Rule028AgeClassificationDebtor rule = new Rule028AgeClassificationDebtor();
        rule.setAgeThresholdDays(30);
        rule.setVelocityWindowHours(24);
        rule.setMinimumTransactionCount(3);
        rule.setVolumeSpikeFactor(3.0);
        rule.setApplyRiskMultipliers(false);

        CustomerContext customer = new CustomerContext("CUST_028", "ACC_028", CustomerContext.CustomerType.INDIVIDUAL,
            CustomerContext.RiskRating.MEDIUM, LocalDate.now().minusDays(5), LocalDate.now().minusDays(5), "TZ", false, false,
            new BigDecimal("1000"), new BigDecimal("500"), new BigDecimal("10000"), 10, null, Set.of("USD"), Map.of());

        LocalDateTime now = LocalDateTime.now();
        history.recordTransaction(tx("D1", "ACC_028", new BigDecimal("700.00"), now.minusHours(5)));
        history.recordTransaction(tx("D2", "ACC_028", new BigDecimal("800.00"), now.minusHours(4)));

        RuleResult result = rule.evaluate(
            tx("D3", "ACC_028", new BigDecimal("900.00"), now),
            customer,
            new RuleContext(history, Instant.now(), Map.of())
        );

        assertTrue(result.triggered());
    }

    private static Transaction tx(String id, String account, BigDecimal amount, LocalDateTime time) {
        return new Transaction(id, "CUST", account, amount, "USD", Transaction.TransactionDirection.DEBIT, time,
            "CP", "CP_ACC", "payment", null);
    }
}
