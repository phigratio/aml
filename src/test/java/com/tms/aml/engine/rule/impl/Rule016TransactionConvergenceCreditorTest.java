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

class Rule016TransactionConvergenceCreditorTest {

    @Test
    void shouldTriggerOnManyUniqueIncomingSenders() {
        InMemoryTransactionHistoryProvider history =
            new InMemoryTransactionHistoryProvider(Duration.ofDays(30), 20_000);

        Rule016TransactionConvergenceCreditor rule = new Rule016TransactionConvergenceCreditor();
        rule.setWindowHours(24);
        rule.setUniqueDebtorThreshold(4);
        rule.setMinimumWindowVolume(new BigDecimal("3000.00"));
        rule.setApplyRiskMultipliers(false);

        CustomerContext customer = customer("CUST_016", "ACC_016", CustomerContext.RiskRating.MEDIUM);
        LocalDateTime now = LocalDateTime.now();

        history.recordTransaction(tx("S1", "ACC_016", "DBTR_1", new BigDecimal("900.00"), now.minusHours(5)));
        history.recordTransaction(tx("S2", "ACC_016", "DBTR_2", new BigDecimal("800.00"), now.minusHours(4)));
        history.recordTransaction(tx("S3", "ACC_016", "DBTR_3", new BigDecimal("700.00"), now.minusHours(3)));

        RuleResult result = rule.evaluate(
            tx("S4", "ACC_016", "DBTR_4", new BigDecimal("1000.00"), now),
            customer,
            new RuleContext(history, Instant.now(), Map.of())
        );

        assertTrue(result.triggered());
        assertTrue(result.severityScore() >= 0.5);
    }

    @Test
    void shouldNotTriggerWhenUniqueSenderCountLow() {
        InMemoryTransactionHistoryProvider history =
            new InMemoryTransactionHistoryProvider(Duration.ofDays(30), 20_000);

        Rule016TransactionConvergenceCreditor rule = new Rule016TransactionConvergenceCreditor();
        rule.setWindowHours(24);
        rule.setUniqueDebtorThreshold(4);
        rule.setApplyRiskMultipliers(false);

        CustomerContext customer = customer("CUST_016B", "ACC_016B", CustomerContext.RiskRating.MEDIUM);
        LocalDateTime now = LocalDateTime.now();

        history.recordTransaction(tx("T1", "ACC_016B", "DBTR_X", new BigDecimal("900.00"), now.minusHours(5)));
        history.recordTransaction(tx("T2", "ACC_016B", "DBTR_X", new BigDecimal("800.00"), now.minusHours(4)));
        history.recordTransaction(tx("T3", "ACC_016B", "DBTR_X", new BigDecimal("700.00"), now.minusHours(3)));

        RuleResult result = rule.evaluate(
            tx("T4", "ACC_016B", "DBTR_X", new BigDecimal("1000.00"), now),
            customer,
            new RuleContext(history, Instant.now(), Map.of())
        );

        assertFalse(result.triggered());
    }

    private static Transaction tx(
        String txId,
        String account,
        String senderAccount,
        BigDecimal amount,
        LocalDateTime txTime
    ) {
        return new Transaction(
            txId,
            "CUST",
            account,
            amount,
            "USD",
            Transaction.TransactionDirection.CREDIT,
            txTime,
            "Sender",
            senderAccount,
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
