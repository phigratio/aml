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

class Rule017TransactionDivergenceDebtorTest {

    @Test
    void shouldTriggerOnHighUniqueReceiverDivergence() {
        InMemoryTransactionHistoryProvider history =
            new InMemoryTransactionHistoryProvider(Duration.ofDays(30), 20_000);

        Rule017TransactionDivergenceDebtor rule = new Rule017TransactionDivergenceDebtor();
        rule.setTimeWindowHours(24);
        rule.setUniqueCreditorThreshold(4);
        rule.setMinimumOutgoingTransactionCount(5);
        rule.setApplyRiskMultipliers(false);

        CustomerContext customer = customer("CUST_017", "ACC_017", CustomerContext.RiskRating.MEDIUM);
        LocalDateTime now = LocalDateTime.now();

        history.recordTransaction(tx("R1", "ACC_017", "BENEF_1", now.minusHours(5)));
        history.recordTransaction(tx("R2", "ACC_017", "BENEF_2", now.minusHours(4)));
        history.recordTransaction(tx("R3", "ACC_017", "BENEF_3", now.minusHours(3)));
        history.recordTransaction(tx("R4", "ACC_017", "BENEF_4", now.minusHours(2)));

        RuleResult result = rule.evaluate(
            tx("R5", "ACC_017", "BENEF_5", now),
            customer,
            new RuleContext(history, Instant.now(), Map.of())
        );

        assertTrue(result.triggered());
        assertTrue(result.severityScore() >= 0.5);
    }

    @Test
    void shouldNotTriggerWhenUniqueReceiversLow() {
        InMemoryTransactionHistoryProvider history =
            new InMemoryTransactionHistoryProvider(Duration.ofDays(30), 20_000);

        Rule017TransactionDivergenceDebtor rule = new Rule017TransactionDivergenceDebtor();
        rule.setTimeWindowHours(24);
        rule.setUniqueCreditorThreshold(4);
        rule.setMinimumOutgoingTransactionCount(5);
        rule.setApplyRiskMultipliers(false);

        CustomerContext customer = customer("CUST_017B", "ACC_017B", CustomerContext.RiskRating.MEDIUM);
        LocalDateTime now = LocalDateTime.now();

        history.recordTransaction(tx("S1", "ACC_017B", "BENEF_X", now.minusHours(5)));
        history.recordTransaction(tx("S2", "ACC_017B", "BENEF_X", now.minusHours(4)));
        history.recordTransaction(tx("S3", "ACC_017B", "BENEF_X", now.minusHours(3)));
        history.recordTransaction(tx("S4", "ACC_017B", "BENEF_X", now.minusHours(2)));

        RuleResult result = rule.evaluate(
            tx("S5", "ACC_017B", "BENEF_X", now),
            customer,
            new RuleContext(history, Instant.now(), Map.of())
        );

        assertFalse(result.triggered());
    }

    private static Transaction tx(String txId, String account, String beneficiary, LocalDateTime txTime) {
        return new Transaction(
            txId,
            "CUST",
            account,
            new BigDecimal("500.00"),
            "USD",
            Transaction.TransactionDirection.DEBIT,
            txTime,
            "Beneficiary",
            beneficiary,
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
