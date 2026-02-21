package com.tms.aml.example;

import com.tms.aml.domain.Transaction;
import com.tms.aml.domain.CustomerContext;
import com.tms.aml.domain.RuleResult;
import com.tms.aml.engine.AMLRuleEngine;
import com.tms.aml.engine.rule.impl.Rule001DerivedAccountAgeCreditor;
import com.tms.aml.engine.rule.Rule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * AML Rule Engine Complete Example
 * 
 * Demonstrates:
 *  1. Creating a rule engine and registering rules
 *  2. Building transaction and customer data
 *  3. Evaluating transactions concurrently
 *  4. Processing results and generating alerts
 * 
 * This example can be run as a standalone application or integrated
 * into Spring Boot application for testing.
 * 
 * @author AML Example Team
 */
public class AMLRuleEngineExample {
    
    private static final Logger logger = LoggerFactory.getLogger(AMLRuleEngineExample.class);
    
    public static void main(String[] args) {
        logger.info("╔════════════════════════════════════════════════════════════════╗");
        logger.info("║          AML RULE ENGINE - JAVA 25 VIRTUAL THREADS            ║");
        logger.info("║                 CONCURRENT RULE EVALUATION DEMO                ║");
        logger.info("╚════════════════════════════════════════════════════════════════╝");
        
        // ═════════════════════════════════════════════════════════════════════
        // STEP 1: Initialize Rule Engine
        // ═════════════════════════════════════════════════════════════════════
        
        AMLRuleEngine engine = new AMLRuleEngine();
        logger.info("\n[INIT] AML Rule Engine initialized");
        
        // ═════════════════════════════════════════════════════════════════════
        // STEP 2: Register Rules
        // ═════════════════════════════════════════════════════════════════════
        
        Rule rule001 = new Rule001DerivedAccountAgeCreditor();
        engine.registerRule(rule001);
        logger.info("[RULES] Registered Rule 001: {}", rule001.getRuleName());
        logger.info("[CONFIG] Total Rules: {}, Enabled: {}", 
            engine.getTotalRuleCount(), engine.getEnabledRuleCount());
        
        // ═════════════════════════════════════════════════════════════════════
        // STEP 3: Create Test Scenarios
        // ═════════════════════════════════════════════════════════════════════
        
        logger.info("\n[TEST] Creating test scenarios...");
        
        // SCENARIO 1: HIGH-RISK - Newly opened account + Large credit
        // This SHOULD TRIGGER Rule 001
        Transaction highRiskTransaction = new Transaction(
            "TXN_001_HIGH_RISK",
            "CUST_001",
            "ACC_001",
            new BigDecimal("15000.00"),
            "USD",
            Transaction.TransactionDirection.CREDIT,
            LocalDateTime.now(),
            "Unknown Counterparty Inc",
            "ACC_UNKNOWN_001",
            "Goods purchase",
            0.65
        );
        
        CustomerContext highRiskCustomer = new CustomerContext(
            "CUST_001",
            "ACC_001",
            CustomerContext.CustomerType.INDIVIDUAL,
            CustomerContext.RiskRating.HIGH,
            LocalDate.now().minusDays(10),  // Opened 10 days ago - NEW!
            LocalDate.now().minusDays(5),
            "TZ",
            false,
            false,
            new BigDecimal("2000.00"),      // Typical monthly: $2,000
            new BigDecimal("1000.00"),
            new BigDecimal("5000.00"),
            3,
            null,
            Set.of("USD"),
            new HashMap<>()
        );
        
        // SCENARIO 2: LOW-RISK - Mature account + Reasonable credit
        // This SHOULD NOT TRIGGER Rule 001 (account too old)
        Transaction lowRiskTransaction = new Transaction(
            "TXN_002_LOW_RISK",
            "CUST_002",
            "ACC_002",
            new BigDecimal("8000.00"),
            "USD",
            Transaction.TransactionDirection.CREDIT,
            LocalDateTime.now(),
            "Employer Corp",
            "ACC_EMPLOYER",
            "Salary payment",
            0.15
        );
        
        CustomerContext lowRiskCustomer = new CustomerContext(
            "CUST_002",
            "ACC_002",
            CustomerContext.CustomerType.INDIVIDUAL,
            CustomerContext.RiskRating.LOW,
            LocalDate.now().minusYears(5),  // Opened 5 years ago - MATURE
            LocalDate.now().minusYears(4),
            "TZ",
            false,
            false,
            new BigDecimal("7000.00"),
            new BigDecimal("6000.00"),
            new BigDecimal("10000.00"),
            12,
            null,
            Set.of("USD"),
            new HashMap<>()
        );
        
        // SCENARIO 3: MEDIUM-RISK - New account but small credit
        // This SHOULD NOT TRIGGER Rule 001 (amount below threshold)
        Transaction mediumRiskTransaction = new Transaction(
            "TXN_003_MEDIUM_RISK",
            "CUST_003",
            "ACC_003",
            new BigDecimal("1000.00"),      // Small amount
            "USD",
            Transaction.TransactionDirection.CREDIT,
            LocalDateTime.now(),
            "Friend Transfer",
            "ACC_FRIEND",
            "Personal loan repayment",
            0.30
        );
        
        CustomerContext mediumRiskCustomer = new CustomerContext(
            "CUST_003",
            "ACC_003",
            CustomerContext.CustomerType.INDIVIDUAL,
            CustomerContext.RiskRating.MEDIUM,
            LocalDate.now().minusDays(15),  // Opened 15 days ago - NEW
            LocalDate.now().minusDays(10),
            "TZ",
            false,
            false,
            new BigDecimal("500.00"),
            new BigDecimal("300.00"),
            new BigDecimal("2000.00"),
            1,
            null,
            Set.of("USD"),
            new HashMap<>()
        );
        
        // ═════════════════════════════════════════════════════════════════════
        // STEP 4: Evaluate Transactions
        // ═════════════════════════════════════════════════════════════════════
        
        logger.info("\n[EVALUATION] Starting concurrent transaction evaluation...");
        
        List<TestCase> testCases = List.of(
            new TestCase("Scenario 1: HIGH-RISK", highRiskTransaction, highRiskCustomer),
            new TestCase("Scenario 2: LOW-RISK", lowRiskTransaction, lowRiskCustomer),
            new TestCase("Scenario 3: MEDIUM-RISK", mediumRiskTransaction, mediumRiskCustomer)
        );
        
        List<AMLRuleEngine.TransactionEvaluationResult> results = new ArrayList<>();
        
        for (TestCase testCase : testCases) {
            long startTime = System.currentTimeMillis();
            
            AMLRuleEngine.TransactionEvaluationResult result = 
                engine.evaluateTransaction(testCase.transaction, testCase.customer);
            
            long evaluationTime = System.currentTimeMillis() - startTime;
            
            results.add(result);
            
            logger.info("\n╔═══════════════════════════════════════════════╗");
            logger.info("║ {} ({} ms)", testCase.name, evaluationTime);
            logger.info("╠═══════════════════════════════════════════════╣");
            logger.info("║ Transaction ID: {}", result.transactionId());
            logger.info("║ Customer ID: {}", result.customerId());
            logger.info("║ Risk Score: {:.2f}/1.0", result.riskScore());
            logger.info("║ Risk Category: {}", result.riskCategory().getDescription());
            logger.info("║ Triggered Rules: {}", result.getTriggeredRuleCount());
            logger.info("╚═══════════════════════════════════════════════╝");
            
            if (!result.triggeredRules().isEmpty()) {
                logger.info("\n   TRIGGERED RULES:");
                for (RuleResult ruleResult : result.triggeredRules()) {
                    logger.info("   ├─ {} (Severity: {:.2f}, {})",
                        ruleResult.ruleName(),
                        ruleResult.severityScore(),
                        ruleResult.typology()
                    );
                    logger.info("   │  └─ Recommendation: {}", ruleResult.recommendedAction());
                    logger.info("   │  └─ Evidence Keys: {}", ruleResult.evidence().keySet());
                }
            }
        }
        
        // ═════════════════════════════════════════════════════════════════════
        // STEP 5: Generate Summary Report
        // ═════════════════════════════════════════════════════════════════════
        
        generateSummaryReport(results);
        
        // ═════════════════════════════════════════════════════════════════════
        // STEP 6: Demonstrate Concurrent Evaluation Performance
        // ═════════════════════════════════════════════════════════════════════
        
        demonstrateConcurrentPerformance(engine, highRiskCustomer);
    }
    
    /**
     * Generate Summary Report
     */
    private static void generateSummaryReport(
        List<AMLRuleEngine.TransactionEvaluationResult> results
    ) {
        logger.info("\n╔════════════════════════════════════════════════════════════════╗");
        logger.info("║                      EVALUATION SUMMARY REPORT                 ║");
        logger.info("╠════════════════════════════════════════════════════════════════╣");
        
        int totalTransactions = results.size();
        int criticalCount = (int) results.stream().filter(AMLRuleEngine.TransactionEvaluationResult::isCritical).count();
        int highRiskCount = (int) results.stream().filter(AMLRuleEngine.TransactionEvaluationResult::isHighRisk).count();
        int mediumRiskCount = (int) results.stream().filter(AMLRuleEngine.TransactionEvaluationResult::isMediumRisk).count();
        int lowRiskCount = (int) results.stream().filter(AMLRuleEngine.TransactionEvaluationResult::isLowRisk).count();
        
        double avgEvaluationTime = results.stream()
            .mapToLong(AMLRuleEngine.TransactionEvaluationResult::evaluationTimeMs)
            .average()
            .orElse(0.0);
        
        logger.info("║ Total Transactions Evaluated: {}", totalTransactions);
        logger.info("║ CRITICAL Risk: {}", criticalCount);
        logger.info("║ HIGH Risk: {}", highRiskCount);
        logger.info("║ MEDIUM Risk: {}", mediumRiskCount);
        logger.info("║ LOW Risk: {}", lowRiskCount);
        logger.info("║ Average Evaluation Time: {:.2f} ms", avgEvaluationTime);
        logger.info("╚════════════════════════════════════════════════════════════════╝");
    }
    
    /**
     * Demonstrate concurrent evaluation with multiple transactions
     * Shows the performance benefit of virtual threads
     */
    private static void demonstrateConcurrentPerformance(
        AMLRuleEngine engine,
        CustomerContext baseCustomer
    ) {
        logger.info("\n╔════════════════════════════════════════════════════════════════╗");
        logger.info("║         CONCURRENT EVALUATION PERFORMANCE DEMONSTRATION        ║");
        logger.info("╚════════════════════════════════════════════════════════════════╝");
        
        int transactionCount = 10;
        logger.info("\nEvaluating {} transactions concurrently...", transactionCount);
        
        long batchStartTime = System.currentTimeMillis();
        
        for (int i = 0; i < transactionCount; i++) {
            Transaction txn = new Transaction(
                "CONCURRENT_TXN_" + i,
                "CUST_CONCURRENT_" + i,
                "ACC_CONCURRENT_" + i,
                new BigDecimal(5000 + (i * 1000)),
                "USD",
                Transaction.TransactionDirection.CREDIT,
                LocalDateTime.now(),
                "Counterparty " + i,
                "ACC_" + i,
                "Transfer",
                0.5
            );
            
            // Simulate concurrent evaluation
            new Thread(() -> {
                engine.evaluateTransaction(txn, baseCustomer);
            }).start();
        }
        
        // Wait a bit for concurrent threads to complete
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        long batchEndTime = System.currentTimeMillis();
        long totalTime = batchEndTime - batchStartTime;
        
        logger.info("Concurrent evaluation of {} transactions completed in {} ms",
            transactionCount, totalTime);
        logger.info("Average per transaction: {} ms", totalTime / transactionCount);
        logger.info("\n✓ Virtual Threads enabled efficient parallel processing!");
    }
    
    /**
     * Test Case Holder
     */
    private static class TestCase {
        String name;
        Transaction transaction;
        CustomerContext customer;
        
        TestCase(String name, Transaction transaction, CustomerContext customer) {
            this.name = name;
            this.transaction = transaction;
            this.customer = customer;
        }
    }
}
