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

class Rule027CommissionedTransactionMirroringDebtorTest {

    @Test
    void shouldTriggerOnCommissionedDebtorMirroring() {
        InMemoryTransactionHistoryProvider history = new InMemoryTransactionHistoryProvider(Duration.ofDays(30), 10_000);
        Rule027CommissionedTransactionMirroringDebtor rule = new Rule027CommissionedTransactionMirroringDebtor();
        rule.setWindowHours(48);
        rule.setMatchThreshold(2);
        rule.setAmountTolerancePercent(0.03);
        rule.setExpectedCommissionPercent(0.03);
        rule.setCommissionTolerancePercent(0.01);
        rule.setApplyRiskMultipliers(false);

        CustomerContext customer = customer("CUST_027", "ACC_027");
        LocalDateTime now = LocalDateTime.now();

        history.recordTransaction(tx("I1", "ACC_027", new BigDecimal("1000.00"), Transaction.TransactionDirection.CREDIT, now.minusHours(10)));
        history.recordTransaction(tx("I2", "ACC_027", new BigDecimal("1500.00"), Transaction.TransactionDirection.CREDIT, now.minusHours(9)));
        history.recordTransaction(tx("O1", "ACC_027", new BigDecimal("970.00"), Transaction.TransactionDirection.DEBIT, now.minusHours(4)));

        RuleResult result = rule.evaluate(
            tx("O2", "ACC_027", new BigDecimal("1455.00"), Transaction.TransactionDirection.DEBIT, now),
            customer,
            new RuleContext(history, Instant.now(), Map.of())
        );

        assertTrue(result.triggered());
    }

    private static Transaction tx(String id, String account, BigDecimal amount, Transaction.TransactionDirection dir, LocalDateTime time) {
        return new Transaction(id, "CUST", account, amount, "USD", dir, time, "CP", "CP_ACC", "payment", null);
    }

    private static CustomerContext customer(String id, String account) {
        return new CustomerContext(id, account, CustomerContext.CustomerType.INDIVIDUAL, CustomerContext.RiskRating.MEDIUM,
            LocalDate.now().minusYears(1), LocalDate.now().minusYears(1), "TZ", false, false,
            new BigDecimal("1000"), new BigDecimal("1000"), new BigDecimal("10000"), 20, null, Set.of("USD"), Map.of());
    }
}
