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

class Rule010IncreasedAccountActivityVolumeDebtorTest {

    @Test
    void shouldTriggerOnVolumeSpikeOverHistoricalBaseline() {
        InMemoryTransactionHistoryProvider history =
            new InMemoryTransactionHistoryProvider(Duration.ofDays(120), 40_000);

        Rule010IncreasedAccountActivityVolumeDebtor rule =
            new Rule010IncreasedAccountActivityVolumeDebtor();
        rule.setCurrentPeriodDays(7);
        rule.setHistoricalPeriodDays(30);
        rule.setSpikeFactor(2.5);
        rule.setMinimumCurrentPeriodTransactionCount(3);
        rule.setApplyRiskMultipliers(false);

        CustomerContext customer = customer("CUST_010", "ACC_010", CustomerContext.RiskRating.MEDIUM);
        LocalDateTime now = LocalDateTime.now();

        // Historical baseline: low/steady outgoing volume
        for (int i = 0; i < 15; i++) {
            history.recordTransaction(tx(
                "H" + i,
                "ACC_010",
                new BigDecimal("100.00"),
                now.minusDays(30 - i)
            ));
        }

        // Current period spike candidate (2 prior + current = 3)
        history.recordTransaction(tx("C1", "ACC_010", new BigDecimal("1500.00"), now.minusDays(3)));
        history.recordTransaction(tx("C2", "ACC_010", new BigDecimal("1600.00"), now.minusDays(2)));

        RuleResult result = rule.evaluate(
            tx("C3", "ACC_010", new BigDecimal("1700.00"), now),
            customer,
            new RuleContext(history, Instant.now(), Map.of())
        );

        assertTrue(result.triggered());
        assertTrue(result.severityScore() >= 0.5);
    }

    @Test
    void shouldNotTriggerWhenCurrentVolumeNearBaseline() {
        InMemoryTransactionHistoryProvider history =
            new InMemoryTransactionHistoryProvider(Duration.ofDays(120), 40_000);

        Rule010IncreasedAccountActivityVolumeDebtor rule =
            new Rule010IncreasedAccountActivityVolumeDebtor();
        rule.setCurrentPeriodDays(7);
        rule.setHistoricalPeriodDays(30);
        rule.setSpikeFactor(3.0);
        rule.setMinimumCurrentPeriodTransactionCount(3);
        rule.setApplyRiskMultipliers(false);

        CustomerContext customer = customer("CUST_010B", "ACC_010B", CustomerContext.RiskRating.MEDIUM);
        LocalDateTime now = LocalDateTime.now();

        for (int i = 0; i < 20; i++) {
            history.recordTransaction(tx(
                "HB" + i,
                "ACC_010B",
                new BigDecimal("500.00"),
                now.minusDays(30 - i)
            ));
        }

        history.recordTransaction(tx("CB1", "ACC_010B", new BigDecimal("400.00"), now.minusDays(3)));
        history.recordTransaction(tx("CB2", "ACC_010B", new BigDecimal("450.00"), now.minusDays(2)));

        RuleResult result = rule.evaluate(
            tx("CB3", "ACC_010B", new BigDecimal("420.00"), now),
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
            Transaction.TransactionDirection.DEBIT,
            txTime,
            "Counterparty",
            "CP_ACC",
            "payment",
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
