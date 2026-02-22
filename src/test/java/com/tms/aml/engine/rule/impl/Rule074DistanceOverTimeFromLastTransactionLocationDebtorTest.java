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

class Rule074DistanceOverTimeFromLastTransactionLocationDebtorTest {

    @Test
    void shouldTriggerOnImpossibleTravelSpeed() {
        InMemoryTransactionHistoryProvider history = new InMemoryTransactionHistoryProvider(Duration.ofDays(30), 10_000);
        Rule074DistanceOverTimeFromLastTransactionLocationDebtor rule = new Rule074DistanceOverTimeFromLastTransactionLocationDebtor();
        rule.setMaxPlausibleSpeedKmh(500.0);
        rule.setMinimumTimeDeltaMinutes(5);

        CustomerContext customer = customer("CUST_074", "ACC_074");
        LocalDateTime now = LocalDateTime.now();

        history.recordTransaction(tx("H1", "ACC_074", now.minusMinutes(30), "geo=40.7128,-74.0060;type=PAYMENT"));

        RuleResult result = rule.evaluate(
            tx("CURR", "ACC_074", now, "geo=34.0522,-118.2437;type=PAYMENT"),
            customer,
            new RuleContext(history, Instant.now(), Map.of())
        );

        assertTrue(result.triggered());
    }

    private static Transaction tx(String id, String account, LocalDateTime at, String purpose) {
        return new Transaction(id, "CUST", account, new BigDecimal("1000.00"), "USD", Transaction.TransactionDirection.DEBIT,
            at, "CP", "CP_ACC", purpose, null);
    }

    private static CustomerContext customer(String id, String account) {
        return new CustomerContext(id, account, CustomerContext.CustomerType.INDIVIDUAL, CustomerContext.RiskRating.MEDIUM,
            LocalDate.now().minusYears(1), LocalDate.now().minusYears(1), "US", false, false,
            new BigDecimal("1000"), new BigDecimal("1000"), new BigDecimal("10000"), 20, null, Set.of("USD"), Map.of());
    }
}
