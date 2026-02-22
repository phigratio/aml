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

class Rule083MultipleAccountsAssociatedWithDebtorTest {

    @Test
    void shouldTriggerWhenLinkedDebtorAccountsExceedThreshold() {
        Rule083MultipleAccountsAssociatedWithDebtor rule = new Rule083MultipleAccountsAssociatedWithDebtor();
        rule.setMultiAccountThreshold(3);

        CustomerContext customer = new CustomerContext("CUST_083", "ACC_083", CustomerContext.CustomerType.INDIVIDUAL,
            CustomerContext.RiskRating.MEDIUM, LocalDate.now().minusYears(1), LocalDate.now().minusYears(1), "US", false, false,
            new BigDecimal("1000"), new BigDecimal("1000"), new BigDecimal("10000"), 20, null, Set.of("USD"),
            Map.of("linked_debtor_accounts", "A1,A2,A3,A4"));

        RuleResult result = rule.evaluate(
            tx("CURR", "ACC_083", LocalDateTime.now()),
            customer,
            new RuleContext(new InMemoryTransactionHistoryProvider(Duration.ofDays(10), 1000), Instant.now(), Map.of())
        );

        assertTrue(result.triggered());
    }

    private static Transaction tx(String id, String account, LocalDateTime at) {
        return new Transaction(id, "CUST", account, new BigDecimal("500.00"), "USD", Transaction.TransactionDirection.DEBIT,
            at, "CP", "CP_ACC", "type=PAYMENT", null);
    }
}
