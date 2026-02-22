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

class Rule006OutgoingTransferSimilarityAmountsTest {

    @Test
    void shouldTriggerOnMultipleSimilarOutgoingAmounts() {
        InMemoryTransactionHistoryProvider history =
            new InMemoryTransactionHistoryProvider(Duration.ofDays(30), 20_000);

        Rule006OutgoingTransferSimilarityAmounts rule = new Rule006OutgoingTransferSimilarityAmounts();
        rule.setObservationWindowHours(24);
        rule.setSimilarityCountThreshold(4);
        rule.setTolerancePercent(0.01);
        rule.setFixedToleranceAmount(new BigDecimal("20.00"));
        rule.setApplyRiskMultipliers(false);

        CustomerContext customer = customer("CUST_006", "ACC_006", CustomerContext.RiskRating.MEDIUM);
        LocalDateTime now = LocalDateTime.now();

        history.recordTransaction(tx("H1", "ACC_006", new BigDecimal("1000.00"), now.minusHours(5)));
        history.recordTransaction(tx("H2", "ACC_006", new BigDecimal("1008.00"), now.minusHours(4)));
        history.recordTransaction(tx("H3", "ACC_006", new BigDecimal("995.00"), now.minusHours(3)));

        RuleResult result = rule.evaluate(
            tx("CURR", "ACC_006", new BigDecimal("1003.00"), now),
            customer,
            new RuleContext(history, Instant.now(), Map.of())
        );

        assertTrue(result.triggered());
        assertTrue(result.severityScore() >= 0.5);
    }

    @Test
    void shouldNotTriggerWhenAmountsAreNotSimilarEnough() {
        InMemoryTransactionHistoryProvider history =
            new InMemoryTransactionHistoryProvider(Duration.ofDays(30), 20_000);

        Rule006OutgoingTransferSimilarityAmounts rule = new Rule006OutgoingTransferSimilarityAmounts();
        rule.setObservationWindowHours(24);
        rule.setSimilarityCountThreshold(4);
        rule.setTolerancePercent(0.001);
        rule.setFixedToleranceAmount(new BigDecimal("5.00"));
        rule.setApplyRiskMultipliers(false);

        CustomerContext customer = customer("CUST_006B", "ACC_006B", CustomerContext.RiskRating.MEDIUM);
        LocalDateTime now = LocalDateTime.now();

        history.recordTransaction(tx("H1B", "ACC_006B", new BigDecimal("1000.00"), now.minusHours(5)));
        history.recordTransaction(tx("H2B", "ACC_006B", new BigDecimal("1250.00"), now.minusHours(4)));
        history.recordTransaction(tx("H3B", "ACC_006B", new BigDecimal("1600.00"), now.minusHours(3)));

        RuleResult result = rule.evaluate(
            tx("CURRB", "ACC_006B", new BigDecimal("1999.00"), now),
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
            "transfer",
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
