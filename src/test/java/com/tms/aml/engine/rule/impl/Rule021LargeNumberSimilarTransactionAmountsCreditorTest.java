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

class Rule021LargeNumberSimilarTransactionAmountsCreditorTest {

    @Test
    void shouldTriggerOnLargeClusterOfSimilarIncomingAmounts() {
        InMemoryTransactionHistoryProvider history =
            new InMemoryTransactionHistoryProvider(Duration.ofDays(60), 20_000);

        Rule021LargeNumberSimilarTransactionAmountsCreditor rule =
            new Rule021LargeNumberSimilarTransactionAmountsCreditor();
        rule.setWindowHours(24);
        rule.setCountThreshold(5);
        rule.setTolerancePercent(0.02);
        rule.setMinimumTotalIncomingVolume(new BigDecimal("0.00"));
        rule.setApplyRiskMultipliers(false);

        CustomerContext customer = customer("CUST_021", "ACC_021", CustomerContext.RiskRating.MEDIUM);
        LocalDateTime now = LocalDateTime.now();

        history.recordTransaction(tx("H1", "ACC_021", new BigDecimal("1000.00"), now.minusHours(5)));
        history.recordTransaction(tx("H2", "ACC_021", new BigDecimal("1012.00"), now.minusHours(4)));
        history.recordTransaction(tx("H3", "ACC_021", new BigDecimal("1009.00"), now.minusHours(3)));
        history.recordTransaction(tx("H4", "ACC_021", new BigDecimal("998.00"), now.minusHours(2)));

        RuleResult result = rule.evaluate(
            tx("CURR", "ACC_021", new BigDecimal("1005.00"), now),
            customer,
            new RuleContext(history, Instant.now(), Map.of())
        );

        assertTrue(result.triggered());
        assertTrue(result.severityScore() >= 0.5);
    }

    @Test
    void shouldNotTriggerWhenClusterCountIsLow() {
        InMemoryTransactionHistoryProvider history =
            new InMemoryTransactionHistoryProvider(Duration.ofDays(60), 20_000);

        Rule021LargeNumberSimilarTransactionAmountsCreditor rule =
            new Rule021LargeNumberSimilarTransactionAmountsCreditor();
        rule.setWindowHours(24);
        rule.setCountThreshold(6);
        rule.setTolerancePercent(0.01);
        rule.setMinimumTotalIncomingVolume(new BigDecimal("0.00"));
        rule.setApplyRiskMultipliers(false);

        CustomerContext customer = customer("CUST_021B", "ACC_021B", CustomerContext.RiskRating.MEDIUM);
        LocalDateTime now = LocalDateTime.now();

        history.recordTransaction(tx("HB1", "ACC_021B", new BigDecimal("1000.00"), now.minusHours(5)));
        history.recordTransaction(tx("HB2", "ACC_021B", new BigDecimal("1010.00"), now.minusHours(4)));
        history.recordTransaction(tx("HB3", "ACC_021B", new BigDecimal("1020.00"), now.minusHours(3)));

        RuleResult result = rule.evaluate(
            tx("CURRB", "ACC_021B", new BigDecimal("980.00"), now),
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
