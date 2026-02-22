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

class Rule090UpstreamTransactionDivergenceDebtorTest {

    @Test
    void shouldTriggerWhenTooManyChannelsUsed() {
        InMemoryTransactionHistoryProvider history = new InMemoryTransactionHistoryProvider(Duration.ofDays(120), 10_000);
        Rule090UpstreamTransactionDivergenceDebtor rule = new Rule090UpstreamTransactionDivergenceDebtor();
        rule.setWindowDays(30);
        rule.setChannelThreshold(3);

        CustomerContext customer = customer("CUST_090", "ACC_090");
        LocalDateTime now = LocalDateTime.now();

        history.recordTransaction(tx("H1", "ACC_090", now.minusDays(5), "channel=BANK;type=PAYMENT"));
        history.recordTransaction(tx("H2", "ACC_090", now.minusDays(4), "channel=MOBILE;type=PAYMENT"));
        history.recordTransaction(tx("H3", "ACC_090", now.minusDays(3), "channel=CRYPTO;type=PAYMENT"));

        RuleResult result = rule.evaluate(
            tx("CURR", "ACC_090", now, "channel=CARD;type=PAYMENT"),
            customer,
            new RuleContext(history, Instant.now(), Map.of())
        );

        assertTrue(result.triggered());
    }

    private static Transaction tx(String id, String account, LocalDateTime at, String purpose) {
        return new Transaction(id, "CUST", account, new BigDecimal("500.00"), "USD", Transaction.TransactionDirection.DEBIT,
            at, "CP", "CP_ACC", purpose, null);
    }

    private static CustomerContext customer(String id, String account) {
        return new CustomerContext(id, account, CustomerContext.CustomerType.INDIVIDUAL, CustomerContext.RiskRating.MEDIUM,
            LocalDate.now().minusYears(1), LocalDate.now().minusYears(1), "US", false, false,
            new BigDecimal("1000"), new BigDecimal("1000"), new BigDecimal("10000"), 20, null, Set.of("USD"), Map.of());
    }
}
