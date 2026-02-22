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

class Rule008OutgoingTransferSimilarityCreditorTest {

    @Test
    void shouldTriggerWhenTransfersConcentrateOnSingleCreditor() {
        InMemoryTransactionHistoryProvider history =
            new InMemoryTransactionHistoryProvider(Duration.ofDays(60), 20_000);

        Rule008OutgoingTransferSimilarityCreditor rule = new Rule008OutgoingTransferSimilarityCreditor();
        rule.setTimeWindowDays(7);
        rule.setCountThreshold(4);
        rule.setMinimumTotalAmount(new BigDecimal("3000.00"));
        rule.setApplyRiskMultipliers(false);

        CustomerContext customer = customer("CUST_008", "ACC_008", CustomerContext.RiskRating.MEDIUM);
        LocalDateTime now = LocalDateTime.now();

        history.recordTransaction(tx("C1", "ACC_008", "BENEF_1", new BigDecimal("900.00"), now.minusDays(2)));
        history.recordTransaction(tx("C2", "ACC_008", "BENEF_1", new BigDecimal("800.00"), now.minusDays(1)));
        history.recordTransaction(tx("C3", "ACC_008", "BENEF_1", new BigDecimal("700.00"), now.minusHours(5)));

        RuleResult result = rule.evaluate(
            tx("C4", "ACC_008", "BENEF_1", new BigDecimal("1000.00"), now),
            customer,
            new RuleContext(history, Instant.now(), Map.of())
        );

        assertTrue(result.triggered());
        assertTrue(result.severityScore() >= 0.5);
    }

    @Test
    void shouldNotTriggerWhenBeneficiariesAreDistributed() {
        InMemoryTransactionHistoryProvider history =
            new InMemoryTransactionHistoryProvider(Duration.ofDays(60), 20_000);

        Rule008OutgoingTransferSimilarityCreditor rule = new Rule008OutgoingTransferSimilarityCreditor();
        rule.setTimeWindowDays(7);
        rule.setCountThreshold(4);
        rule.setApplyRiskMultipliers(false);

        CustomerContext customer = customer("CUST_008B", "ACC_008B", CustomerContext.RiskRating.MEDIUM);
        LocalDateTime now = LocalDateTime.now();

        history.recordTransaction(tx("D1", "ACC_008B", "BENEF_A", new BigDecimal("900.00"), now.minusDays(2)));
        history.recordTransaction(tx("D2", "ACC_008B", "BENEF_B", new BigDecimal("800.00"), now.minusDays(1)));
        history.recordTransaction(tx("D3", "ACC_008B", "BENEF_C", new BigDecimal("700.00"), now.minusHours(5)));

        RuleResult result = rule.evaluate(
            tx("D4", "ACC_008B", "BENEF_D", new BigDecimal("1000.00"), now),
            customer,
            new RuleContext(history, Instant.now(), Map.of())
        );

        assertFalse(result.triggered());
    }

    private static Transaction tx(
        String txId,
        String account,
        String beneficiaryAccount,
        BigDecimal amount,
        LocalDateTime txTime
    ) {
        return new Transaction(
            txId,
            "CUST",
            account,
            amount,
            "USD",
            Transaction.TransactionDirection.DEBIT,
            txTime,
            "Beneficiary",
            beneficiaryAccount,
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
