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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Rule020LargeTransactionAmountVsHistoryCreditorTest {

    @Test
    void shouldTriggerOnLargeIncomingAmountVsHistory() {
        InMemoryTransactionHistoryProvider history =
            new InMemoryTransactionHistoryProvider(Duration.ofDays(120), 40_000);

        Rule020LargeTransactionAmountVsHistoryCreditor rule =
            new Rule020LargeTransactionAmountVsHistoryCreditor();
        rule.setHistoricalWindowDays(30);
        rule.setThresholdMultiplier(4.0);
        rule.setMinimumHistoricalTransactions(5);
        rule.setApplyRiskMultipliers(false);

        CustomerContext customer = customer("CUST_020", "ACC_020", CustomerContext.RiskRating.MEDIUM);
        LocalDateTime now = LocalDateTime.now();

        for (int i = 0; i < 7; i++) {
            history.recordTransaction(tx("H" + i, "ACC_020", new BigDecimal("400.00"), now.minusDays(7 - i)));
        }

        RuleResult result = rule.evaluate(
            tx("CURR", "ACC_020", new BigDecimal("2500.00"), now),
            customer,
            new RuleContext(history, Instant.now(), Map.of())
        );

        assertTrue(result.triggered());
        assertTrue(result.severityScore() >= 0.5);
    }

    @Test
    void shouldNotTriggerWhenIncomingAmountNearBaseline() {
        InMemoryTransactionHistoryProvider history =
            new InMemoryTransactionHistoryProvider(Duration.ofDays(120), 40_000);

        Rule020LargeTransactionAmountVsHistoryCreditor rule =
            new Rule020LargeTransactionAmountVsHistoryCreditor();
        rule.setHistoricalWindowDays(30);
        rule.setThresholdMultiplier(5.0);
        rule.setMinimumHistoricalTransactions(5);
        rule.setApplyRiskMultipliers(false);

        CustomerContext customer = customer("CUST_020B", "ACC_020B", CustomerContext.RiskRating.MEDIUM);
        LocalDateTime now = LocalDateTime.now();

        for (int i = 0; i < 7; i++) {
            history.recordTransaction(tx("HB" + i, "ACC_020B", new BigDecimal("500.00"), now.minusDays(7 - i)));
        }

        RuleResult result = rule.evaluate(
            tx("CURRB", "ACC_020B", new BigDecimal("1200.00"), now),
            customer,
            new RuleContext(history, Instant.now(), Map.of())
        );

        assertFalse(result.triggered());
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
            "Counterparty",
            "CP_ACC",
            "incoming",
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
            new BigDecimal("1000.00"),
            new BigDecimal("10000.00"),
            20,
            null,
            Set.of("USD"),
            Map.of()
        );
    }
}
