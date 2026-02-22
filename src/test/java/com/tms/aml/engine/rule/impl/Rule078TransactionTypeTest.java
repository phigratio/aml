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

import static org.junit.jupiter.api.Assertions.assertTrue;

class Rule078TransactionTypeTest {

    @Test
    void shouldTriggerOnHighRiskTypeOutsideProfile() {
        InMemoryTransactionHistoryProvider history = new InMemoryTransactionHistoryProvider(Duration.ofDays(120), 20_000);
        Rule078TransactionType rule = new Rule078TransactionType();
        rule.setHistoricalWindowDays(90);
        rule.setMinimumProfileSamples(5);

        CustomerContext customer = customer("CUST_078", "ACC_078");
        LocalDateTime now = LocalDateTime.now();

        for (int i = 0; i < 6; i++) {
            history.recordTransaction(tx("H" + i, "ACC_078", now.minusDays(10 - i), "type=LOCAL_TRANSFER"));
        }

        RuleResult result = rule.evaluate(
            tx("CURR", "ACC_078", now, "type=CRYPTO_CONVERSION"),
            customer,
            new RuleContext(history, Instant.now(), Map.of())
        );

        assertTrue(result.triggered());
    }

    private static Transaction tx(String id, String account, LocalDateTime at, String purpose) {
        return new Transaction(id, "CUST", account, new BigDecimal("500.00"), "USD", Transaction.TransactionDirection.DEBIT,
            at, "CP", "CP_ACC", purpose, null);
    }

    private static CustomerContext customer(String id, String account) {
        return new CustomerContext(id, account, CustomerContext.CustomerType.INDIVIDUAL, CustomerContext.RiskRating.MEDIUM,
            LocalDate.now().minusYears(1), LocalDate.now().minusYears(1), "US", false, false,
            new BigDecimal("1000"), new BigDecimal("1000"), new BigDecimal("10000"), 20, null, Set.of("USD"), Map.of());
    }
}
