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

class Rule054SyntheticDataCheckBenfordsLawDebtorTest {

    @Test
    void shouldTriggerOnStrongBenfordDeviationForDebtor() {
        InMemoryTransactionHistoryProvider history = new InMemoryTransactionHistoryProvider(Duration.ofDays(200), 50_000);
        Rule054SyntheticDataCheckBenfordsLawDebtor rule = new Rule054SyntheticDataCheckBenfordsLawDebtor();
        rule.setHistoricalWindowDays(120);
        rule.setMinimumSamples(100);
        rule.setMadThreshold(0.06);

        CustomerContext customer = customer("CUST_054", "ACC_054");
        LocalDateTime now = LocalDateTime.now();

        for (int i = 0; i < 120; i++) {
            history.recordTransaction(tx("H" + i, "ACC_054", new BigDecimal("9" + String.format("%02d", i % 90) + ".00"), now.minusDays(100 - (i % 100))));
        }

        RuleResult result = rule.evaluate(
            tx("CURR", "ACC_054", new BigDecimal("999.00"), now),
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
