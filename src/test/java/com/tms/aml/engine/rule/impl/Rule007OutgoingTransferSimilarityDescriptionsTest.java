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

class Rule007OutgoingTransferSimilarityDescriptionsTest {

    @Test
    void shouldTriggerOnHighlySimilarDescriptions() {
        InMemoryTransactionHistoryProvider history =
            new InMemoryTransactionHistoryProvider(Duration.ofDays(30), 20_000);

        Rule007OutgoingTransferSimilarityDescriptions rule =
            new Rule007OutgoingTransferSimilarityDescriptions();
        rule.setObservationWindowHours(24);
        rule.setMinimumOccurrenceCount(4);
        rule.setDescriptionSimilarityThreshold(0.90);
        rule.setApplyRiskMultipliers(false);

        CustomerContext customer = customer("CUST_007", "ACC_007", CustomerContext.RiskRating.MEDIUM);
        LocalDateTime now = LocalDateTime.now();

        history.recordTransaction(tx("D1", "ACC_007", "invoice 2024 payment", now.minusHours(5)));
        history.recordTransaction(tx("D2", "ACC_007", "invoice 2024 payment", now.minusHours(4)));
        history.recordTransaction(tx("D3", "ACC_007", "invoice 2024 payment", now.minusHours(3)));

        RuleResult result = rule.evaluate(
            tx("D4", "ACC_007", "invoice 2024 payment", now),
            customer,
            new RuleContext(history, Instant.now(), Map.of())
        );

        assertTrue(result.triggered());
        assertTrue(result.severityScore() >= 0.5);
    }

    @Test
    void shouldNotTriggerForDissimilarDescriptions() {
        InMemoryTransactionHistoryProvider history =
            new InMemoryTransactionHistoryProvider(Duration.ofDays(30), 20_000);

        Rule007OutgoingTransferSimilarityDescriptions rule =
            new Rule007OutgoingTransferSimilarityDescriptions();
        rule.setObservationWindowHours(24);
        rule.setMinimumOccurrenceCount(4);
        rule.setDescriptionSimilarityThreshold(0.90);
        rule.setApplyRiskMultipliers(false);

        CustomerContext customer = customer("CUST_007B", "ACC_007B", CustomerContext.RiskRating.MEDIUM);
        LocalDateTime now = LocalDateTime.now();

        history.recordTransaction(tx("E1", "ACC_007B", "salary disbursement", now.minusHours(5)));
        history.recordTransaction(tx("E2", "ACC_007B", "equipment purchase", now.minusHours(4)));
        history.recordTransaction(tx("E3", "ACC_007B", "tax payment", now.minusHours(3)));

        RuleResult result = rule.evaluate(
            tx("E4", "ACC_007B", "refund to client", now),
            customer,
            new RuleContext(history, Instant.now(), Map.of())
        );

        assertFalse(result.triggered());
    }

    private static Transaction tx(String txId, String account, String description, LocalDateTime txTime) {
        return new Transaction(
            txId,
            "CUST",
            account,
            new BigDecimal("1000.00"),
            "USD",
            Transaction.TransactionDirection.DEBIT,
            txTime,
            "Counterparty",
            "CP_ACC",
            description,
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
