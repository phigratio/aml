package com.tms.aml.integration;

import com.tms.aml.domain.CustomerContext;
import com.tms.aml.domain.Transaction;
import com.tms.aml.engine.AMLRuleEngine;
import com.tms.aml.engine.history.TransactionHistoryProvider;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("integration")
@Testcontainers(disabledWithoutDocker = true)
class PostgresHistoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("aml_test")
        .withUsername("aml")
        .withPassword("aml");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("aml.engine.history.provider", () -> "postgres");
        registry.add("aml.engine.history.table-name", () -> "aml_transaction_history");
    }

    @Autowired
    private TransactionHistoryProvider historyProvider;

    @Autowired
    private AMLRuleEngine amlRuleEngine;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void ensureSchemaMigrated() {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate();
    }

    @Test
    void shouldPersistAndQueryHistoryViaPostgresProvider() {
        Transaction older = tx(
            "INT_TX_001",
            "ACC_INT",
            Transaction.TransactionDirection.CREDIT,
            new BigDecimal("1000.00"),
            LocalDateTime.now().minusDays(2)
        );

        Transaction newer = tx(
            "INT_TX_002",
            "ACC_INT",
            Transaction.TransactionDirection.DEBIT,
            new BigDecimal("2500.00"),
            LocalDateTime.now().minusDays(1)
        );

        historyProvider.recordTransaction(older);
        historyProvider.recordTransaction(newer);

        assertTrue(historyProvider
            .findInboundTransactions("ACC_INT", LocalDateTime.now().minusDays(3), LocalDateTime.now())
            .stream()
            .anyMatch(t -> "INT_TX_001".equals(t.transactionId())));

        assertTrue(historyProvider
            .findLatestTransaction("ACC_INT", LocalDateTime.now(), Transaction.TransactionDirection.DEBIT)
            .isPresent());
    }

    @Test
    void shouldEvaluateRulesUsingPostgresBackedHistory() {
        Transaction initialDebit = tx(
            "INT_TX_003",
            "ACC_RULE",
            Transaction.TransactionDirection.DEBIT,
            new BigDecimal("100.00"),
            LocalDateTime.now().minusDays(120)
        );
        historyProvider.recordTransaction(initialDebit);

        var result = amlRuleEngine.evaluateTransaction(
            tx(
                "INT_TX_004",
                "ACC_RULE",
                Transaction.TransactionDirection.DEBIT,
                new BigDecimal("7000.00"),
                LocalDateTime.now()
            ),
            customer("CUST_RULE", "ACC_RULE")
        );

        assertNotNull(result);
        assertFalse(result.allRuleResults().isEmpty());
        assertTrue(result.allRuleResults().stream().anyMatch(r -> "004".equals(r.ruleId())));
    }

    private static Transaction tx(
        String txId,
        String accountNumber,
        Transaction.TransactionDirection direction,
        BigDecimal amount,
        LocalDateTime dateTime
    ) {
        return new Transaction(
            txId,
            "CUST_INT",
            accountNumber,
            amount,
            "USD",
            direction,
            dateTime,
            "Counterparty",
            "CP_ACC",
            "integration-test",
            null
        );
    }

    private static CustomerContext customer(String customerId, String accountNumber) {
        return new CustomerContext(
            customerId,
            accountNumber,
            CustomerContext.CustomerType.INDIVIDUAL,
            CustomerContext.RiskRating.HIGH,
            LocalDate.now().minusYears(2),
            LocalDate.now().minusYears(2),
            "TZ",
            false,
            false,
            new BigDecimal("1500.00"),
            new BigDecimal("1200.00"),
            new BigDecimal("9000.00"),
            20,
            null,
            Set.of("USD"),
            Map.of()
        );
    }
}
