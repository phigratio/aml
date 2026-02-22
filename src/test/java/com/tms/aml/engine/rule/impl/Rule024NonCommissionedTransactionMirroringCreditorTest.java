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

class Rule024NonCommissionedTransactionMirroringCreditorTest {

    @Test
    void shouldTriggerWhenMirroredIncomingOutgoingPairsExceedThreshold() {
        InMemoryTransactionHistoryProvider history =
            new InMemoryTransactionHistoryProvider(Duration.ofDays(30), 20_000);

        Rule024NonCommissionedTransactionMirroringCreditor rule =
            new Rule024NonCommissionedTransactionMirroringCreditor();
        rule.setWindowHours(48);
        rule.setPairThreshold(2);
        rule.setAmountTolerancePercent(0.05);
        rule.setApplyRiskMultipliers(false);

        CustomerContext customer = customer("CUST_024", "ACC_024", CustomerContext.RiskRating.MEDIUM);
        LocalDateTime now = LocalDateTime.now();

        history.recordTransaction(tx("I1", "ACC_024", new BigDecimal("1000.00"), Transaction.TransactionDirection.CREDIT, now.minusHours(8), "SRC_1"));
        history.recordTransaction(tx("I2", "ACC_024", new BigDecimal("1200.00"), Transaction.TransactionDirection.CREDIT, now.minusHours(7), "SRC_2"));
        history.recordTransaction(tx("O1", "ACC_024", new BigDecimal("980.00"), Transaction.TransactionDirection.DEBIT, now.minusHours(4), "DST_1"));

        RuleResult result = rule.evaluate(
            tx("O2", "ACC_024", new BigDecimal("1185.00"), Transaction.TransactionDirection.DEBIT, now, "DST_2"),
            customer,
            new RuleContext(history, Instant.now(), Map.of())
        );

        assertTrue(result.triggered());
        assertTrue(result.severityScore() >= 0.5);
    }

    @Test
    void shouldNotTriggerWhenMirroringPairsBelowThreshold() {
        InMemoryTransactionHistoryProvider history =
            new InMemoryTransactionHistoryProvider(Duration.ofDays(30), 20_000);

        Rule024NonCommissionedTransactionMirroringCreditor rule =
            new Rule024NonCommissionedTransactionMirroringCreditor();
        rule.setWindowHours(48);
        rule.setPairThreshold(2);
        rule.setAmountTolerancePercent(0.01);
        rule.setApplyRiskMultipliers(false);

        CustomerContext customer = customer("CUST_024B", "ACC_024B", CustomerContext.RiskRating.MEDIUM);
        LocalDateTime now = LocalDateTime.now();

        history.recordTransaction(tx("IB1", "ACC_024B", new BigDecimal("1000.00"), Transaction.TransactionDirection.CREDIT, now.minusHours(8), "SRC_1"));
        history.recordTransaction(tx("IB2", "ACC_024B", new BigDecimal("1400.00"), Transaction.TransactionDirection.CREDIT, now.minusHours(7), "SRC_2"));
        history.recordTransaction(tx("OB1", "ACC_024B", new BigDecimal("850.00"), Transaction.TransactionDirection.DEBIT, now.minusHours(4), "DST_1"));

        RuleResult result = rule.evaluate(
            tx("OB2", "ACC_024B", new BigDecimal("1100.00"), Transaction.TransactionDirection.DEBIT, now, "DST_2"),
            customer,
            new RuleContext(history, Instant.now(), Map.of())
        );

        assertFalse(result.triggered());
    }

    private static Transaction tx(
        String txId,
        String account,
        BigDecimal amount,
        Transaction.TransactionDirection direction,
        LocalDateTime txTime,
        String counterpartyAccount
    ) {
        return new Transaction(
            txId,
            "CUST",
            account,
            amount,
            "USD",
            direction,
            txTime,
            "Counterparty",
            counterpartyAccount,
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
