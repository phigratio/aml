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

class Rule048LargeTransactionAmountVsHistoryDebtorTest {

    @Test
    void shouldTriggerOnLargeOutgoingAmountVsHistory() {
        InMemoryTransactionHistoryProvider history = new InMemoryTransactionHistoryProvider(Duration.ofDays(90), 20_000);
        Rule048LargeTransactionAmountVsHistoryDebtor rule = new Rule048LargeTransactionAmountVsHistoryDebtor();
        rule.setHistoricalWindowDays(30);
        rule.setThresholdMultiplier(4.0);
        rule.setMinimumHistoricalTransactions(5);
        rule.setApplyRiskMultipliers(false);

        CustomerContext customer = customer("CUST_048", "ACC_048");
        LocalDateTime now = LocalDateTime.now();

        for (int i = 0; i < 6; i++) {
            history.recordTransaction(tx("H" + i, "ACC_048", new BigDecimal("400.00"), now.minusDays(6 - i)));
        }

        RuleResult result = rule.evaluate(
            tx("CURR", "ACC_048", new BigDecimal("2200.00"), now),
            customer,
            new RuleContext(history, Instant.now(), Map.of())
        );

        assertTrue(result.triggered());
    }

    private static Transaction tx(String id, String account, BigDecimal amount, LocalDateTime time) {
        return new Transaction(id, "CUST", account, amount, "USD", Transaction.TransactionDirection.DEBIT,
            time, "CP", "CP_ACC", "payment", null);
    }

    private static CustomerContext customer(String id, String account) {
        return new CustomerContext(id, account, CustomerContext.CustomerType.INDIVIDUAL, CustomerContext.RiskRating.MEDIUM,
            LocalDate.now().minusYears(1), LocalDate.now().minusYears(1), "TZ", false, false,
            new BigDecimal("1000"), new BigDecimal("1000"), new BigDecimal("10000"), 20, null, Set.of("USD"), Map.of());
    }
}
