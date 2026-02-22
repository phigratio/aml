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

class Rule018ExceptionallyLargeOutgoingTransferDebtorTest {

    @Test
    void shouldTriggerOnExceptionallyLargeOutgoingTransfer() {
        InMemoryTransactionHistoryProvider history =
            new InMemoryTransactionHistoryProvider(Duration.ofDays(60), 20_000);

        Rule018ExceptionallyLargeOutgoingTransferDebtor rule =
            new Rule018ExceptionallyLargeOutgoingTransferDebtor();
        rule.setHistoricalWindowDays(30);
        rule.setThresholdMultiplier(4.0);
        rule.setMinimumHistoricalTransactions(5);
        rule.setAbsoluteMinimumAmountFloor(new BigDecimal("1000.00"));
        rule.setApplyRiskMultipliers(false);

        CustomerContext customer = customer("CUST_018", "ACC_018", CustomerContext.RiskRating.MEDIUM);
        LocalDateTime now = LocalDateTime.now();

        for (int i = 0; i < 6; i++) {
            history.recordTransaction(tx("H" + i, "ACC_018", new BigDecimal("500.00"), now.minusDays(10 - i)));
        }

        RuleResult result = rule.evaluate(
            tx("CURR", "ACC_018", new BigDecimal("3000.00"), now),
            customer,
            new RuleContext(history, Instant.now(), Map.of())
        );

        assertTrue(result.triggered());
        assertTrue(result.severityScore() >= 0.5);
    }

    @Test
    void shouldNotTriggerWhenAmountNearBaseline() {
        InMemoryTransactionHistoryProvider history =
            new InMemoryTransactionHistoryProvider(Duration.ofDays(60), 20_000);

        Rule018ExceptionallyLargeOutgoingTransferDebtor rule =
            new Rule018ExceptionallyLargeOutgoingTransferDebtor();
        rule.setHistoricalWindowDays(30);
        rule.setThresholdMultiplier(5.0);
        rule.setMinimumHistoricalTransactions(5);
        rule.setApplyRiskMultipliers(false);

        CustomerContext customer = customer("CUST_018B", "ACC_018B", CustomerContext.RiskRating.MEDIUM);
        LocalDateTime now = LocalDateTime.now();

        for (int i = 0; i < 6; i++) {
            history.recordTransaction(tx("HB" + i, "ACC_018B", new BigDecimal("500.00"), now.minusDays(10 - i)));
        }

        RuleResult result = rule.evaluate(
            tx("CURRB", "ACC_018B", new BigDecimal("1200.00"), now),
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
