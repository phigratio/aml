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

class Rule044SuccessfulTransactionsFromDebtorIncludingNewOneTest {

    @Test
    void shouldTriggerOnDebtorFrequencySpike() {
        InMemoryTransactionHistoryProvider history = new InMemoryTransactionHistoryProvider(Duration.ofDays(120), 20_000);
        Rule044SuccessfulTransactionsFromDebtorIncludingNewOne rule = new Rule044SuccessfulTransactionsFromDebtorIncludingNewOne();
        rule.setCurrentPeriodDays(7);
        rule.setHistoricalPeriodDays(35);
        rule.setCountSpikeFactor(2.0);
        rule.setApplyRiskMultipliers(false);

        CustomerContext customer = customer("CUST_044", "ACC_044");
        LocalDateTime now = LocalDateTime.now();

        for (int i = 0; i < 6; i++) {
            history.recordTransaction(tx("H" + i, "ACC_044", now.minusDays(30 - i)));
        }

        history.recordTransaction(tx("C1", "ACC_044", now.minusDays(2)));
        history.recordTransaction(tx("C2", "ACC_044", now.minusDays(1)));

        RuleResult result = rule.evaluate(
            tx("C3", "ACC_044", now),
            customer,
            new RuleContext(history, Instant.now(), Map.of())
        );

        assertTrue(result.triggered());
    }

    private static Transaction tx(String id, String account, LocalDateTime time) {
        return new Transaction(id, "CUST", account, new BigDecimal("100.00"), "USD",
            Transaction.TransactionDirection.DEBIT, time, "CP", "CP_ACC", "payment", null);
    }

    private static CustomerContext customer(String id, String account) {
        return new CustomerContext(id, account, CustomerContext.CustomerType.INDIVIDUAL, CustomerContext.RiskRating.MEDIUM,
            LocalDate.now().minusYears(1), LocalDate.now().minusYears(1), "TZ", false, false,
            new BigDecimal("1000"), new BigDecimal("1000"), new BigDecimal("10000"), 20, null, Set.of("USD"), Map.of());
    }
}
