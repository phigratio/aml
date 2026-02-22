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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class Rule075DistanceFromHabitualLocationsDebtorTest {

    @Test
    void shouldTriggerWhenFarFromHabitualLocations() {
        InMemoryTransactionHistoryProvider history = new InMemoryTransactionHistoryProvider(Duration.ofDays(120), 20_000);
        Rule075DistanceFromHabitualLocationsDebtor rule = new Rule075DistanceFromHabitualLocationsDebtor();
        rule.setHistoricalWindowDays(90);
        rule.setMinimumHistoricalLocations(5);
        rule.setDistanceThresholdKm(100.0);
        rule.setApplyRiskMultipliers(false);

        Map<String, Object> attrs = new HashMap<>();
        attrs.put("home_geo", "40.7128,-74.0060");

        CustomerContext customer = new CustomerContext("CUST_075", "ACC_075", CustomerContext.CustomerType.INDIVIDUAL,
            CustomerContext.RiskRating.MEDIUM, LocalDate.now().minusYears(1), LocalDate.now().minusYears(1), "US", false, false,
            new BigDecimal("1000"), new BigDecimal("1000"), new BigDecimal("10000"), 20, null, Set.of("USD"), attrs);

        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 5; i++) {
            history.recordTransaction(tx("H" + i, "ACC_075", now.minusDays(10 - i), "geo=40.7130,-74.0050;type=PAYMENT"));
        }

        RuleResult result = rule.evaluate(
            tx("CURR", "ACC_075", now, "geo=34.0522,-118.2437;type=PAYMENT"),
            customer,
            new RuleContext(history, Instant.now(), Map.of())
        );

        assertTrue(result.triggered());
    }

    private static Transaction tx(String id, String account, LocalDateTime at, String purpose) {
        return new Transaction(id, "CUST", account, new BigDecimal("1000.00"), "USD", Transaction.TransactionDirection.DEBIT,
            at, "CP", "CP_ACC", purpose, null);
    }
}
