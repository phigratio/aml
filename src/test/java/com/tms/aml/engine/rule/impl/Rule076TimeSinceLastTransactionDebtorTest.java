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

class Rule076TimeSinceLastTransactionDebtorTest {

    @Test
    void shouldTriggerOnDormancyReactivation() {
        InMemoryTransactionHistoryProvider history = new InMemoryTransactionHistoryProvider(Duration.ofDays(300), 10_000);
        Rule076TimeSinceLastTransactionDebtor rule = new Rule076TimeSinceLastTransactionDebtor();
        rule.setInactivityThresholdDays(90);
        rule.setBurstThresholdMinutes(60);

        CustomerContext customer = customer("CUST_076", "ACC_076");
        LocalDateTime now = LocalDateTime.now();

        history.recordTransaction(tx("H1", "ACC_076", now.minusDays(120)));

        RuleResult result = rule.evaluate(
            tx("CURR", "ACC_076", now),
            customer,
            new RuleContext(history, Instant.now(), Map.of())
        );

        assertTrue(result.triggered());
    }

    private static Transaction tx(String id, String account, LocalDateTime at) {
        return new Transaction(id, "CUST", account, new BigDecimal("1000.00"), "USD", Transaction.TransactionDirection.DEBIT,
            at, "CP", "CP_ACC", "type=PAYMENT", null);
    }

    private static CustomerContext customer(String id, String account) {
        return new CustomerContext(id, account, CustomerContext.CustomerType.INDIVIDUAL, CustomerContext.RiskRating.MEDIUM,
            LocalDate.now().minusYears(1), LocalDate.now().minusYears(1), "US", false, false,
            new BigDecimal("1000"), new BigDecimal("1000"), new BigDecimal("10000"), 20, null, Set.of("USD"), Map.of());
    }
}
