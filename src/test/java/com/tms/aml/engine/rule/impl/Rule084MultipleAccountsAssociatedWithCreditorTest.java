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

class Rule084MultipleAccountsAssociatedWithCreditorTest {

    @Test
    void shouldTriggerWhenLinkedCreditorAccountsExceedThreshold() {
        Rule084MultipleAccountsAssociatedWithCreditor rule = new Rule084MultipleAccountsAssociatedWithCreditor();
        rule.setMultiAccountThreshold(3);

        CustomerContext customer = new CustomerContext("CUST_084", "ACC_084", CustomerContext.CustomerType.INDIVIDUAL,
            CustomerContext.RiskRating.MEDIUM, LocalDate.now().minusYears(1), LocalDate.now().minusYears(1), "US", false, false,
            new BigDecimal("1000"), new BigDecimal("1000"), new BigDecimal("10000"), 20, null, Set.of("USD"),
            Map.of("linked_creditor_accounts", "C1,C2,C3"));

        RuleResult result = rule.evaluate(
            tx("CURR", "ACC_084", LocalDateTime.now()),
            customer,
            new RuleContext(new InMemoryTransactionHistoryProvider(Duration.ofDays(10), 1000), Instant.now(), Map.of())
        );

        assertTrue(result.triggered());
    }

    private static Transaction tx(String id, String account, LocalDateTime at) {
        return new Transaction(id, "CUST", account, new BigDecimal("500.00"), "USD", Transaction.TransactionDirection.CREDIT,
            at, "CP", "CP_ACC", "type=PAYMENT", null);
    }
}
