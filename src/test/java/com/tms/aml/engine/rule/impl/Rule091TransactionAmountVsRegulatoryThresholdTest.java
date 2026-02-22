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

class Rule091TransactionAmountVsRegulatoryThresholdTest {

    @Test
    void shouldTriggerWhenAmountExceedsJurisdictionThreshold() {
        Rule091TransactionAmountVsRegulatoryThreshold rule = new Rule091TransactionAmountVsRegulatoryThreshold();
        rule.setDefaultThreshold(new BigDecimal("10000.00"));
        rule.setJurisdictionThresholds(Map.of("US", new BigDecimal("10000.00")));

        CustomerContext customer = new CustomerContext("CUST_091", "ACC_091", CustomerContext.CustomerType.INDIVIDUAL,
            CustomerContext.RiskRating.MEDIUM, LocalDate.now().minusYears(1), LocalDate.now().minusYears(1), "US", false, false,
            new BigDecimal("1000"), new BigDecimal("1000"), new BigDecimal("10000"), 20, null, Set.of("USD"), Map.of());

        RuleResult result = rule.evaluate(
            tx("CURR", "ACC_091", new BigDecimal("12000.00"), LocalDateTime.now()),
            customer,
            new RuleContext(new InMemoryTransactionHistoryProvider(Duration.ofDays(10), 1000), Instant.now(), Map.of())
        );

        assertTrue(result.triggered());
    }

    private static Transaction tx(String id, String account, BigDecimal amount, LocalDateTime at) {
        return new Transaction(id, "CUST", account, amount, "USD", Transaction.TransactionDirection.CREDIT,
            at, "CP", "CP_ACC", "type=WIRE", null);
    }
}
