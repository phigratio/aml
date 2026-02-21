package com.tms.aml.engine.rule;

import com.tms.aml.domain.Transaction;
import com.tms.aml.domain.CustomerContext;
import com.tms.aml.domain.RuleResult;

/**
 * Rule Interface - Core Contract for AML Rule Implementations
 * 
 * This interface defines the contract that all AML rules must implement to
 * participate in the concurrent RuleEngine evaluation framework. Each rule
 * encapsulates a specific AML detection pattern (typology) and can be
 * evaluated independently and concurrently using Java 25 Virtual Threads.
 * 
 * Design Pattern: Strategy Pattern with Functional Programming support
 * Architecture: Plugin-based rule engine supporting dynamic rule loading
 * 
 * Concurrency Model:
 * - All implementations MUST be thread-safe and side-effect free
 * - evaluate() method will be invoked by Virtual Threads (via StructuredTaskScope)
 * - No shared mutable state between rule instances
 * - Each rule evaluation is independent and can run in parallel with others
 * 
 * @author AML Engineering Team
 * @since Java 25 (Virtual Threads / Structured Concurrency)
 */
public interface Rule {
    
    /**
     * Returns the unique identifier for this rule
     * Format: Three digit numeric string (e.g., "001", "042")
     * Must be globally unique within the rule engine
     * 
     * @return Rule ID
     */
    String getRuleId();
    
    /**
     * Returns the human-readable name of this rule
     * Format: "Rule NNN: Brief Description"
     * Used in reports, logs, and alerts
     * 
     * @return Rule name
     */
    String getRuleName();
    
    /**
     * Returns the AML Typology this rule detects
     * Examples: "Placement", "Layering", "Integration", "Money Mule", etc.
     * Used for categorization and regulatory reporting
     * 
     * @return AML Typology string
     */
    String getTypology();
    
    /**
     * Returns the regulatory foundation/guideline basis for this rule
     * Examples: "FFIEC AML Manual Appendix F", "FATF Recommendation 10-12", etc.
     * Important for audit trails and regulatory justification
     * 
     * @return Regulatory basis description
     */
    String getRegulatoryBasis();
    
    /**
     * PRIMARY RULE EVALUATION METHOD
     * 
     * Evaluates this rule against a transaction and customer context.
     * This is the core business logic method that implements the specific
     * AML detection pattern (Condition / Formula) for this rule.
     * 
     * CONCURRENCY GUARANTEES:
     * - This method will be called from a Virtual Thread (Java 25)
     * - Must not access shared mutable state
     * - Must not block indefinitely
     * - Must be idempotent (same inputs = same outputs)
     * 
     * PERFORMANCE EXPECTATIONS:
     * - Target: <10ms per evaluation (40+ rules × 10ms = <400ms per transaction)
     * - P99: <50ms (allows for some I/O or complex calculations)
     * - No network calls or external system dependencies
     * 
     * @param transaction The transaction to evaluate (immutable, from Virtual Thread)
     * @param customer The customer context data (immutable, cached from repository)
     * 
     * @return RuleResult containing:
     *         - triggered: boolean indicating if rule condition was met
     *         - severityScore: 0.0-1.0 score indicating risk magnitude
     *         - evidence: Map of rule-specific attributes explaining the trigger
     *         - recommendedAction: Suggested next step for investigation
     * 
     * @throws RuleEvaluationException if evaluation cannot complete due to data issues
     *         (Note: Business rule mismatches should NOT throw; they set triggered=false)
     */
    RuleResult evaluate(Transaction transaction, CustomerContext customer)
        throws RuleEvaluationException;
    
    /**
     * Returns whether this rule is currently enabled/active
     * Allows for dynamic rule management without redeployment
     * 
     * @return true if this rule should be evaluated, false otherwise
     */
    boolean isEnabled();
    
    /**
     * Returns the priority/criticality of this rule (0-100)
     * Used for result aggregation and alert prioritization
     * Higher values indicate more critical detection patterns
     * Examples: 95 for PEP/Sanctions, 80 for newly opened accounts, 30 for minor anomalies
     * 
     * @return Priority score 0-100
     */
    int getPriority();
    
    /**
     * Custom Exception for Rule Evaluation Errors
     * Distinguishes data validation errors from business logic mismatches
     */
    class RuleEvaluationException extends Exception {
        
        private final String ruleId;
        private final String transactionId;
        
        public RuleEvaluationException(String ruleId, String transactionId, 
                                       String message) {
            super(message);
            this.ruleId = ruleId;
            this.transactionId = transactionId;
        }
        
        public RuleEvaluationException(String ruleId, String transactionId,
                                       String message, Throwable cause) {
            super(message, cause);
            this.ruleId = ruleId;
            this.transactionId = transactionId;
        }
        
        public String getRuleId() {
            return ruleId;
        }
        
        public String getTransactionId() {
            return transactionId;
        }
    }
}
