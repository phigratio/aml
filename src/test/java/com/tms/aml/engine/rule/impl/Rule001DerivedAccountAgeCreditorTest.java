package com.tms.aml.engine.rule.impl;

import com.tms.aml.domain.CustomerContext;
import com.tms.aml.domain.RuleResult;
import com.tms.aml.domain.Transaction;
import com.tms.aml.engine.history.NoOpTransactionHistoryProvider;
import com.tms.aml.engine.rule.RuleContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class Rule001DerivedAccountAgeCreditorTest {

    @Test
    void shouldNotFailWhenMonthlyAverageCreditIsZero() throws Exception {
        Rule001DerivedAccountAgeCreditor rule = new Rule001DerivedAccountAgeCreditor();
        rule.setAgeThresholdDays(30);
        rule.setAmountThreshold(new BigDecimal("500.00"));

        Transaction transaction = new Transaction(
            "TX_001",
            "CUST_001",
            "ACC_001",
            new BigDecimal("1000.00"),
            "USD",
            Transaction.TransactionDirection.CREDIT,
            LocalDateTime.now(),
            "Sender",
            "SENDER_001",
            "Test payment",
            null
        );

        CustomerContext customer = new CustomerContext(
            "CUST_001",
            "ACC_001",
            CustomerContext.CustomerType.INDIVIDUAL,
            CustomerContext.RiskRating.MEDIUM,
            LocalDate.now().minusDays(5),
            LocalDate.now().minusDays(10),
            "TZ",
            false,
            false,
            BigDecimal.ZERO,
            new BigDecimal("100.00"),
            new BigDecimal("1000.00"),
            3,
            null,
            Set.of("USD"),
            Map.of()
        );

        RuleResult result = rule.evaluate(
            transaction,
            customer,
            new RuleContext(new NoOpTransactionHistoryProvider(), Instant.now(), Map.of())
        );

        assertTrue(result.evidence().containsKey("transaction_vs_baseline_ratio"));
    }
}
