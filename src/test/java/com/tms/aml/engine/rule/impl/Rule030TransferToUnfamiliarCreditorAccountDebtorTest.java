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

class Rule030TransferToUnfamiliarCreditorAccountDebtorTest {

    @Test
    void shouldTriggerOnTransferToNewCreditor() {
        InMemoryTransactionHistoryProvider history = new InMemoryTransactionHistoryProvider(Duration.ofDays(365), 10_000);
        Rule030TransferToUnfamiliarCreditorAccountDebtor rule = new Rule030TransferToUnfamiliarCreditorAccountDebtor();
        rule.setLookbackDays(180);
        rule.setMinimumAmount(new BigDecimal("500.00"));
        rule.setApplyRiskMultipliers(false);

        CustomerContext customer = customer("CUST_030", "ACC_030");
        LocalDateTime now = LocalDateTime.now();

        history.recordTransaction(tx("H1", "ACC_030", "OLD_CP", new BigDecimal("700.00"), now.minusDays(10)));

        RuleResult result = rule.evaluate(
            tx("CURR", "ACC_030", "NEW_CP", new BigDecimal("900.00"), now),
            customer,
            new RuleContext(history, Instant.now(), Map.of())
        );

        assertTrue(result.triggered());
    }

    private static Transaction tx(String id, String account, String cpAccount, BigDecimal amount, LocalDateTime time) {
        return new Transaction(id, "CUST", account, amount, "USD", Transaction.TransactionDirection.DEBIT,
            time, "CP", cpAccount, "payment", null);
    }

    private static CustomerContext customer(String id, String account) {
        return new CustomerContext(id, account, CustomerContext.CustomerType.INDIVIDUAL, CustomerContext.RiskRating.MEDIUM,
            LocalDate.now().minusYears(1), LocalDate.now().minusYears(1), "TZ", false, false,
            new BigDecimal("1000"), new BigDecimal("1000"), new BigDecimal("10000"), 20, null, Set.of("USD"), Map.of());
    }
}
