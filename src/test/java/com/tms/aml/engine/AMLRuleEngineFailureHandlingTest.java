package com.tms.aml.engine;

import com.tms.aml.domain.CustomerContext;
import com.tms.aml.domain.RuleResult;
import com.tms.aml.domain.Transaction;
import com.tms.aml.engine.history.NoOpTransactionHistoryProvider;
import com.tms.aml.engine.rule.Rule;
import com.tms.aml.engine.rule.RuleContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AMLRuleEngineFailureHandlingTest {

    @Test
    void shouldReturnFailedRuleResultWhenRuleThrows() {
        AMLRuleEngine engine = new AMLRuleEngine(new NoOpTransactionHistoryProvider());
        engine.registerRule(new PassingRule());
        engine.registerRule(new FailingRule());

        AMLRuleEngine.TransactionEvaluationResult result = engine.evaluateTransaction(transaction(), customer());

        assertEquals(2, result.allRuleResults().size());
        assertFalse(result.triggeredRules().stream().anyMatch(r -> "999".equals(r.ruleId())));

        RuleResult failed = result.allRuleResults().stream()
            .filter(r -> "999".equals(r.ruleId()))
            .findFirst()
            .orElseThrow();

        assertEquals("ENGINE_FAILURE", failed.riskCategoryId());
        assertEquals("RULE_EVALUATION_ERROR", failed.evidence().get("failure_type"));
        assertEquals(0.0, failed.severityScore());
    }

    @Test
    void shouldNotCreateSarCaseWhenNoRulesTrigger() {
        AMLRuleEngine engine = new AMLRuleEngine(new NoOpTransactionHistoryProvider());
        engine.registerRule(new PassingRule());

        AMLRuleEngine.TransactionEvaluationResult result = engine.evaluateTransaction(transaction(), customer());

        assertFalse(result.isCritical());
        assertNotNull(result.allRuleResults());
        assertTrue(result.triggeredRules().isEmpty());
        assertNull(result.sarCase());
    }

    private static Transaction transaction() {
        return new Transaction(
            "TX_TEST",
            "CUST_TEST",
            "ACC_TEST",
            new BigDecimal("100.00"),
            "USD",
            Transaction.TransactionDirection.CREDIT,
            LocalDateTime.now(),
            "Sender",
            "SENDER_ACC",
            "test",
            null
        );
    }

    private static CustomerContext customer() {
        return new CustomerContext(
            "CUST_TEST",
            "ACC_TEST",
            CustomerContext.CustomerType.INDIVIDUAL,
            CustomerContext.RiskRating.LOW,
            LocalDate.now().minusYears(2),
            LocalDate.now().minusYears(2),
            "TZ",
            false,
            false,
            new BigDecimal("100.00"),
            new BigDecimal("100.00"),
            new BigDecimal("1000.00"),
            4,
            null,
            Set.of("USD"),
            Map.of()
        );
    }

    private static class PassingRule implements Rule {
        @Override
        public String getRuleId() { return "100"; }

        @Override
        public String getRuleName() { return "Passing Rule"; }

        @Override
        public String getTypology() { return "Test"; }

        @Override
        public String getRegulatoryBasis() { return "Test"; }

        @Override
        public RuleResult evaluate(Transaction transaction, CustomerContext customer, RuleContext context) {
            return RuleResult.builder()
                .ruleId(getRuleId())
                .ruleName(getRuleName())
                .triggered(false)
                .severityScore(0.0)
                .typology(getTypology())
                .riskCategoryId("TEST")
                .evaluationTimeMs(1L)
                .transactionId(transaction.transactionId())
                .customerId(customer.customerId())
                .evidence(Map.of())
                .recommendedAction("none")
                .regulatoryBaseline(getRegulatoryBasis())
                .evaluatedAt(java.time.Instant.now())
                .build();
        }

        @Override
        public boolean isEnabled() { return true; }

        @Override
        public int getPriority() { return 1; }
    }

    private static class FailingRule implements Rule {
        @Override
        public String getRuleId() { return "999"; }

        @Override
        public String getRuleName() { return "Failing Rule"; }

        @Override
        public String getTypology() { return "Test"; }

        @Override
        public String getRegulatoryBasis() { return "Test"; }

        @Override
        public RuleResult evaluate(
            Transaction transaction,
            CustomerContext customer,
            RuleContext context
        ) throws RuleEvaluationException {
            throw new RuleEvaluationException(getRuleId(), transaction.transactionId(), "forced failure");
        }

        @Override
        public boolean isEnabled() { return true; }

        @Override
        public int getPriority() { return 1; }
    }
}
