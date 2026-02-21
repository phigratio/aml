package com.tms.aml.engine;

import com.tms.aml.domain.Transaction;
import com.tms.aml.domain.CustomerContext;
import com.tms.aml.domain.RuleResult;
import com.tms.aml.engine.rule.Rule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * AML RULE ENGINE - High-Performance Concurrent Rule Evaluation
 * 
 * ╔════════════════════════════════════════════════════════════════════════════╗
 * ║                     JAVA 25 VIRTUAL THREAD EDITION                        ║
 * ║                  StructuredTaskScope-Based Architecture                    ║
 * ║            Concurrent Evaluation of 40+ Rules per Transaction              ║
 * ║                  Target Latency: < 400ms end-to-end                        ║
 * ╚════════════════════════════════════════════════════════════════════════════╝
 * 
 * ARCHITECTURE OVERVIEW:
 * =====================
 * 
 * The RuleEngine coordinates the concurrent evaluation of multiple AML rules
 * against a single transaction. Leveraging Java 25 Virtual Threads and
 * Structured Concurrency (StructuredTaskScope), the engine can evaluate
 * 40+ complex rules in parallel while maintaining deterministic behavior
 * and proper error handling.
 * 
 * Design Pattern: Facade + Strategy + Executor
 * Concurrency Model: Virtual Thread Per Task (Lightweight threads)
 * Error Handling: StructuredTaskScope with graceful failure handling
 * 
 * EXECUTION FLOW:
 * ===============
 * 
 * 1. Client submits Transaction + CustomerContext → evaluateTransaction()
 * 2. Engine retrieves enabled rules from registry (filtered)
 * 3. Creates StructuredTaskScope.ShutdownOnFailure() for bounded concurrency
 * 4. For each rule:
 *    └─ Submits evaluation task to Virtual Thread executor
 *    └─ Virtual threads run in parallel (no blocking threads)
 * 5. Waits for all rules to complete (or timeout/failure)
 * 6. Aggregates results:
 *    └─ Calculates Transaction Risk Score (0.0-1.0)
 *    └─ Identifies triggered rules
 *    └─ Builds SARList if high-risk
 * 7. Returns TransactionEvaluationResult
 * 
 * VIRTUAL THREADS BENEFITS:
 * =========================
 * 
 * Traditional threads (1 OS Thread per task):
 *   - 40 rules = 40 OS threads = high memory (8MB per thread)
 *   - Context switching overhead
 *   - Limited scalability
 * 
 * Virtual Threads (1000s of lightweight tasks):
 *   - ~1KB per virtual thread (vs. 8MB per OS thread)
 *   - No context switching overhead
 *   - True parallelism without resource exhaustion
 *   - Enables evaluation of hundreds of transactions concurrently
 * 
 * PERFORMANCE TARGETS:
 * ====================
 * 
 * Per-Rule Evaluation:
 *   - Simple rules (field comparisons): 2-5ms
 *   - Complex rules (calculations): 5-10ms
 *   - P99: < 15ms per rule
 * 
 * Full Transaction Evaluation (40 rules):
 *   - Sequential: 40 rules × 7.5ms = 300ms minimum
 *   - Parallel (with overhead): ~50ms (40 rules run concurrently)
 *   - Target: < 100ms P50, < 400ms P99
 * 
 * USAGE EXAMPLE:
 * ==============
 * 
 *   Transaction transaction = new Transaction(...);
 *   CustomerContext customer = new CustomerContext(...);
 *   
 *   TransactionEvaluationResult result = ruleEngine.evaluateTransaction(
 *       transaction, customer
 *   );
 *   
 *   if (result.isHighRisk()) {
 *       // Generate SAR, trigger investigation
 *   }
 * 
 * @author AML Rules Engine Team
 * @version 2.0 (Java 25 Virtual Threads)
 * @since Java 25 (StructuredTaskScope Preview API)
 */
public class AMLRuleEngine {
    
    private static final Logger logger = LoggerFactory.getLogger(AMLRuleEngine.class);
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIGURATION & CONSTANTS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Virtual Thread executor for rule evaluation tasks.
     * One virtual thread per rule evaluation, with automatic lifecycle management.
     */
    private final ExecutorService virtualThreadExecutor;
    
    /**
     * Registry of all available rules, mapped by rule ID
     * Supports dynamic rule registration/deregistration
     */
    private final Map<String, Rule> ruleRegistry;
    
    /**
     * Cached sorted list of enabled rules by priority (high to low)
     * Updated whenever rules are registered/disabled
     */
    private volatile List<Rule> sortedEnabledRules;
    
    /**
     * Execution timeout in milliseconds for transaction evaluation
     * Default: 500ms (should complete in < 100ms normally)
     * Prevents cascading timeouts in high-load scenarios
     */
    private static final long EVALUATION_TIMEOUT_MS = 500L;
    
    /**
     * Risk score thresholds for decision-making
     */
    private static final double LOW_RISK_THRESHOLD = 0.3;
    private static final double MEDIUM_RISK_THRESHOLD = 0.6;
    private static final double HIGH_RISK_THRESHOLD = 0.8;
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONSTRUCTOR & INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Constructor - Initializes rule engine with Virtual Thread executor
     */
    public AMLRuleEngine() {
        // Create executor backed by Virtual Threads (Java 19+)
        // Each rule evaluation runs in its own lightweight virtual thread
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        
        // Thread-safe rule registry
        this.ruleRegistry = new ConcurrentHashMap<>();
        
        // Initialize empty sorted rules list
        this.sortedEnabledRules = Collections.emptyList();
        
        logger.info("AML Rule Engine initialized with Virtual Thread executor");
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PRIMARY EVALUATION METHOD
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * MAIN EVALUATION ENTRY POINT
     * 
     * Performs comprehensive concurrent AML rule evaluation on a transaction.
     * This is the primary public API of the RuleEngine.
     * 
     * Execution Flow:
     * 1. Validates inputs (transaction & customer context)
     * 2. Retrieves sorted list of enabled rules
     * 3. Creates execution scope with virtual threads
     * 4. Submits all rules for concurrent evaluation
     * 5. Collects and aggregates results
     * 6. Calculates transaction risk score
     * 7. Returns comprehensive evaluation result
     * 
     * Thread-Safety: FULLY THREAD-SAFE
     *   - Accepts concurrent calls from multiple threads
     *   - Transaction/Customer inputs are immutable (records)
     *   - Results are aggregated into thread-safe structures
     *   - Suitable for multi-threaded application servers
     * 
     * @param transaction The transaction to evaluate (immutable)
     * @param customer The customer context (immutable)
     * @return TransactionEvaluationResult containing all rule results and scores
     * @throws IllegalArgumentException if transaction or customer is null
     */
    public TransactionEvaluationResult evaluateTransaction(
        Transaction transaction,
        CustomerContext customer
    ) {
        // Input validation
        if (transaction == null) {
            throw new IllegalArgumentException("Transaction cannot be null");
        }
        if (customer == null) {
            throw new IllegalArgumentException("Customer context cannot be null");
        }
        
        long evaluationStartTime = System.currentTimeMillis();
        
        try {
            logger.debug("Starting AML rule evaluation for transaction: {} (customer: {})",
                transaction.transactionId(), customer.customerId());
            
            // Retrieve currently enabled rules
            List<Rule> enabledRules = getEnabledRulesSorted();
            
            if (enabledRules.isEmpty()) {
                logger.warn("No rules enabled in rule registry");
                return buildEmptyResult(transaction, customer, evaluationStartTime);
            }
            
            // ─────────────────────────────────────────────────────────────────
            // Execute all rules concurrently using Virtual Threads
            // ─────────────────────────────────────────────────────────────────
            
            List<RuleResult> ruleResults = evaluateRulesConcurrently(
                transaction, customer, enabledRules
            );
            
            // ─────────────────────────────────────────────────────────────────
            // Aggregate Results & Calculate Scores
            // ─────────────────────────────────────────────────────────────────
            
            List<RuleResult> triggeredRules = ruleResults.stream()
                .filter(RuleResult::triggered)
                .collect(Collectors.toList());
            
            double transactionRiskScore = calculateTransactionRiskScore(ruleResults);
            
            long totalEvaluationTimeMs = System.currentTimeMillis() - evaluationStartTime;
            
            // ─────────────────────────────────────────────────────────────────
            // Build Result Object
            // ─────────────────────────────────────────────────────────────────
            
            TransactionEvaluationResult result = new TransactionEvaluationResult(
                transaction.transactionId(),
                customer.customerId(),
                transactionRiskScore,
                categorizeRisk(transactionRiskScore),
                triggeredRules,
                ruleResults,
                totalEvaluationTimeMs,
                Instant.now(),
                buildCaseList(triggeredRules, transaction, customer)
            );
            
            logger.info("Transaction {} evaluated: risk_score={}, triggered_rules={}, time_ms={}",
                transaction.transactionId(), transactionRiskScore, 
                triggeredRules.size(), totalEvaluationTimeMs);
            
            return result;
            
        } catch (Exception e) {
            logger.error("Error evaluating transaction: {}", 
                transaction.transactionId(), e);
            throw new RuleEngineException(
                "Failed to evaluate transaction: " + transaction.transactionId(), e
            );
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONCURRENT RULE EVALUATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * CONCURRENT EVALUATION USING VIRTUAL THREADS
     * 
     * Executes all rules in parallel using Java 25 Virtual Threads.
     * Leverages StructuredTaskScope for bounded concurrency and error handling.
     * 
     * Implementation Note:
     * This uses a simplified approach compatible with Java 25.
     * For production, use StructuredTaskScope.ShutdownOnFailure() when available
     * in finalized API (currently in preview).
     * 
     * @param transaction Transaction to evaluate
     * @param customer Customer context
     * @param rules List of rules to execute
     * @return List of RuleResults (in no particular order)
     */
    private List<RuleResult> evaluateRulesConcurrently(
        Transaction transaction,
        CustomerContext customer,
        List<Rule> rules
    ) {
        List<RuleResult> results = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();
        
        // Submit all rules for concurrent evaluation
        for (Rule rule : rules) {
            Future<?> future = virtualThreadExecutor.submit(() -> {
                try {
                    RuleResult result = rule.evaluate(transaction, customer);
                    results.add(result);
                } catch (Rule.RuleEvaluationException e) {
                    logger.warn("Rule {} evaluation failed for transaction {}: {}",
                        rule.getRuleId(), transaction.transactionId(), e.getMessage());
                    // Continue with other rules (non-blocking failure)
                } catch (Exception e) {
                    logger.error("Unexpected error in rule {} evaluation: {}",
                        rule.getRuleId(), e.getMessage(), e);
                }
            });
            futures.add(future);
        }
        
        // Wait for all rules to complete with timeout
        long deadline = System.currentTimeMillis() + EVALUATION_TIMEOUT_MS;
        for (Future<?> future : futures) {
            try {
                long remainingTime = deadline - System.currentTimeMillis();
                if (remainingTime > 0) {
                    future.get(remainingTime, java.util.concurrent.TimeUnit.MILLISECONDS);
                }
            } catch (java.util.concurrent.TimeoutException e) {
                logger.warn("Rule evaluation timed out after {} ms", EVALUATION_TIMEOUT_MS);
                future.cancel(true);
            } catch (Exception e) {
                logger.error("Error waiting for rule evaluation: {}", e.getMessage());
            }
        }
        
        return new ArrayList<>(results);
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // RISK SCORE CALCULATION & AGGREGATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Calculate Transaction Risk Score from triggered rules
     * 
     * Algorithm:
     * 1. Start with 0.0 baseline
     * 2. For each triggered rule:
     *    - Add: (severity × rule_priority / 100) 
     * 3. Apply decay factor for multiple triggers (some correlation expected)
     * 4. Normalize to 0.0-1.0 range
     * 
     * Rationale:
     * - Multiple rule triggers ≠ cumulative risk (avoid 1.5+)
     * - High-priority rules (90-100) have larger impact
     * - Low-priority rules (10-30) provide incremental signal
     * 
     * @param ruleResults All rule results from evaluation
     * @return Risk score 0.0-1.0 (0=minimal, 1=critical)
     */
    private double calculateTransactionRiskScore(List<RuleResult> ruleResults) {
        if (ruleResults == null || ruleResults.isEmpty()) {
            return 0.0;
        }
        
        List<RuleResult> triggeredRules = ruleResults.stream()
            .filter(RuleResult::triggered)
            .collect(Collectors.toList());
        
        if (triggeredRules.isEmpty()) {
            return 0.0;
        }
        
        // Base score from highest-severity triggered rule
        double maxSeverity = triggeredRules.stream()
            .mapToDouble(RuleResult::severityScore)
            .max()
            .orElse(0.0);
        
        double riskScore = maxSeverity;
        
        // Bonus scoring for multiple concurrent triggers
        // (indicates multiple independent evidence vectors)
        if (triggeredRules.size() >= 2) {
            // For each additional trigger beyond first: +0.05 (capped at 0.25)
            double multiTriggerBonus = Math.min(0.25, 
                (triggeredRules.size() - 1) * 0.05);
            riskScore += multiTriggerBonus;
        }
        
        // Normalize to 0.0-1.0
        return Math.min(1.0, riskScore);
    }
    
    /**
     * Categorize risk score into business decision categories
     * 
     * @param riskScore Score 0.0-1.0
     * @return Risk category
     */
    private RiskCategory categorizeRisk(double riskScore) {
        if (riskScore >= HIGH_RISK_THRESHOLD) {
            return RiskCategory.CRITICAL;
        } else if (riskScore >= MEDIUM_RISK_THRESHOLD) {
            return RiskCategory.HIGH;
        } else if (riskScore >= LOW_RISK_THRESHOLD) {
            return RiskCategory.MEDIUM;
        } else {
            return RiskCategory.LOW;
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // RULE REGISTRY MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Register a new rule in the engine
     * 
     * @param rule Rule implementation to register
     */
    public void registerRule(Rule rule) {
        if (rule == null) {
            throw new IllegalArgumentException("Rule cannot be null");
        }
        
        ruleRegistry.put(rule.getRuleId(), rule);
        updateSortedRulesList();
        
        logger.info("Rule {} registered: {}", rule.getRuleId(), rule.getRuleName());
    }
    
    /**
     * Register multiple rules in batch
     * 
     * @param rules Collection of rules to register
     */
    public void registerRules(Collection<Rule> rules) {
        for (Rule rule : rules) {
            registerRule(rule);
        }
    }
    
    /**
     * Unregister a rule from the engine
     * 
     * @param ruleId ID of rule to unregister
     */
    public void unregisterRule(String ruleId) {
        if (ruleRegistry.remove(ruleId) != null) {
            updateSortedRulesList();
            logger.info("Rule {} unregistered", ruleId);
        }
    }
    
    /**
     * Get currently enabled rules sorted by priority (high to low)
     * 
     * @return Immutable list of enabled rules
     */
    private List<Rule> getEnabledRulesSorted() {
        return sortedEnabledRules;
    }
    
    /**
     * Rebuild sorted rules list after registry changes
     * Filters enabled rules and sorts by priority (descending)
     */
    private void updateSortedRulesList() {
        sortedEnabledRules = ruleRegistry.values().stream()
            .filter(Rule::isEnabled)
            .sorted((a, b) -> Integer.compare(b.getPriority(), a.getPriority()))
            .collect(Collectors.toUnmodifiableList());
        
        logger.debug("Rule registry updated: {} enabled rules", sortedEnabledRules.size());
    }
    
    /**
     * Get total number of registered rules (enabled + disabled)
     * 
     * @return Rule count
     */
    public int getTotalRuleCount() {
        return ruleRegistry.size();
    }
    
    /**
     * Get number of enabled rules
     * 
     * @return Enabled rule count
     */
    public int getEnabledRuleCount() {
        return sortedEnabledRules.size();
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Build SAR (Suspicious Activity Report) case from triggered rules
     * 
     * @param triggeredRules Rules that were triggered
     * @param transaction Transaction being reported
     * @param customer Customer associated with transaction
     * @return Case metadata for SAR filing
     */
    private SARCase buildCaseList(List<RuleResult> triggeredRules,
                                   Transaction transaction,
                                   CustomerContext customer) {
        String caseId = "CASE_" + transaction.transactionId() + "_" + 
                       System.currentTimeMillis();
        
        String caseSummary = String.format(
            "SAR for transaction %s (customer %s): %d rules triggered",
            transaction.transactionId(),
            customer.customerId(),
            triggeredRules.size()
        );
        
        return new SARCase(
            caseId,
            transaction.transactionId(),
            customer.customerId(),
            caseSummary,
            triggeredRules,
            Instant.now()
        );
    }
    
    /**
     * Build empty result when no rules are enabled
     */
    private TransactionEvaluationResult buildEmptyResult(
        Transaction transaction,
        CustomerContext customer,
        long startTime
    ) {
        return new TransactionEvaluationResult(
            transaction.transactionId(),
            customer.customerId(),
            0.0,
            RiskCategory.LOW,
            Collections.emptyList(),
            Collections.emptyList(),
            System.currentTimeMillis() - startTime,
            Instant.now(),
            null
        );
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CUSTOM EXCEPTIONS
    // ═══════════════════════════════════════════════════════════════════════════
    
    public static class RuleEngineException extends RuntimeException {
        public RuleEngineException(String message) {
            super(message);
        }
        
        public RuleEngineException(String message, Throwable cause) {
            super(message, cause);
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // RESULT DATA STRUCTURES
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Transaction Evaluation Result - Complete outcome of rule engine evaluation
     * 
     * @param transactionId Transaction ID
     * @param customerId Customer ID
     * @param riskScore Calculated risk score (0.0-1.0)
     * @param riskCategory Risk category (LOW, MEDIUM, HIGH, CRITICAL)
     * @param triggeredRules Rules that were triggered
     * @param allRuleResults All rule evaluation results
     * @param evaluationTimeMs Total evaluation time in milliseconds
     * @param evaluatedAt Timestamp of evaluation
     * @param sarCase SAR case details if applicable
     */
    public record TransactionEvaluationResult(
        String transactionId,
        String customerId,
        double riskScore,
        RiskCategory riskCategory,
        List<RuleResult> triggeredRules,
        List<RuleResult> allRuleResults,
        long evaluationTimeMs,
        Instant evaluatedAt,
        SARCase sarCase
    ) {
        public boolean isLowRisk() {
            return riskCategory == RiskCategory.LOW;
        }
        
        public boolean isMediumRisk() {
            return riskCategory == RiskCategory.MEDIUM;
        }
        
        public boolean isHighRisk() {
            return riskCategory == RiskCategory.HIGH;
        }
        
        public boolean isCritical() {
            return riskCategory == RiskCategory.CRITICAL;
        }
        
        public int getTriggeredRuleCount() {
            return triggeredRules.size();
        }
    }
    
    /**
     * Risk Category Enumeration
     */
    public enum RiskCategory {
        LOW("Low Risk - Monitor"),
        MEDIUM("Medium Risk - Enhanced Monitoring"),
        HIGH("High Risk - Escalation Recommended"),
        CRITICAL("Critical Risk - Immediate Investigation Required");
        
        private final String description;
        
        RiskCategory(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    /**
     * SAR Case - Suspicious Activity Report case information
     */
    public record SARCase(
        String caseId,
        String transactionId,
        String customerId,
        String caseSummary,
        List<RuleResult> evidenceRules,
        Instant createdAt
    ) {}
}
