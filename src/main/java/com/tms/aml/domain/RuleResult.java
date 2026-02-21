package com.tms.aml.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * RuleResult Record - Immutable Rule Evaluation Output
 * 
 * Encapsulates the outcome of a single AML rule evaluation. This record is
 * returned from all Rule implementations and aggregated by the RuleEngine
 * for comprehensive transaction risk assessment.
 * 
 * CRITICAL DATA STRUCTURE: Used in concurrent evaluation, must be thread-safe
 * and immutable (guaranteed by Java records).
 * 
 * @param ruleId Unique identifier for the rule (e.g., "001" for Rule 001)
 * @param ruleName Human-readable rule name
 * @param triggered Boolean flag indicating if the rule condition was met
 * @param severityScore Risk severity on a 0.0-1.0 scale (0=lowest, 1=critical)
 * @param typology Associated AML typology (Placement, Layering, Integration, etc.)
 * @param riskCategoryId Classification code for the detected risk
 * @param evaluationTimeMs Execution time in milliseconds (for monitoring)
 * @param transactionId Reference to evaluated transaction
 * @param customerId Reference to evaluated customer
 * @param evidence Map of rule-specific evidence attributes (reason for triggering)
 * @param recommendedAction Suggested investigator action if rule triggers
 * @param regulatoryBaseline Reference to regulatory guideline that inspired this rule
 * @param evaluatedAt Timestamp when rule was evaluated
 */
public record RuleResult(
    String ruleId,
    String ruleName,
    boolean triggered,
    Double severityScore,
    String typology,
    String riskCategoryId,
    Long evaluationTimeMs,
    String transactionId,
    String customerId,
    Map<String, Object> evidence,
    String recommendedAction,
    String regulatoryBaseline,
    Instant evaluatedAt
) {
    
    /**
     * AML Typology Enumeration - FATF & FinCEN Standard Classifications
     * These represent the three primary phases of money laundering as defined
     * by the Financial Action Task Force (FATF).
     */
    public enum AMLTypology {
        
        // Placement Phase - Introduction of illicit funds into financial system
        PLACEMENT_DEPOSIT("Placement via Deposit"),
        PLACEMENT_STRUCTURING("Placement via Structuring/Smurfing"),
        PLACEMENT_TRADE_BASED("Trade-Based Money Laundering"),
        
        // Layering Phase - Masking the illicit origin through complex transactions
        LAYERING_TRANSFER("Layering through Transfers"),
        LAYERING_TRADE_FINANCE("Layering via Trade Finance"),
        LAYERING_CORRESPONDENT_BANKING("Layering via Correspondent Banking"),
        
        // Integration Phase - Reintroduction of laundered funds as legitimate income
        INTEGRATION_BUSINESS_INVESTMENT("Integration via Business Investment"),
        INTEGRATION_REAL_ESTATE("Integration via Real Estate"),
        INTEGRATION_CASH_WITHDRAWAL("Integration via Cash Withdrawal"),
        
        // Derivative Indicators - Secondary signals and mule patterns
        MONEY_MULE_ACCOUNT("Money Mule Account Activity"),
        UNUSUAL_PATTERN("Unusual Customer Behavioral Pattern"),
        RAPID_FUND_MOVEMENT("Rapid Transfer/Circular Flow"),
        
        // Other Classifications
        KYC_DEFICIENCY("KYC/CDD Deficiency"),
        SANCTIONS_RELATED("Sanctions-Related Activity"),
        OTHER("Other/Unclassified");
        
        private final String description;
        
        AMLTypology(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    /**
     * Risk Category IDs - Standard FinCEN & Central Bank Classifications
     * Used for reporting, case management, and regulatory filing
     */
    public enum RiskCategory {
        RC001("Structured Deposit Avoidance"),
        RC002("Unusual Deposit Patterns"),
        RC003("Rapid Fund Movement"),
        RC004("Newly Opened Account Activity"),
        RC005("High-Risk Jurisdiction Activity"),
        RC006("PEP/Sanctioned Transactions"),
        RC007("Business Inconsistency"),
        RC008("Cross-Border Red Flags"),
        RC009("Threshold Reporting Avoidance"),
        RC010("Correspondent Banking Abuse");
        
        private final String description;
        
        RiskCategory(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    /**
     * Compact constructor for validation
     */
    public RuleResult {
        if (ruleId == null || ruleId.isBlank()) {
            throw new IllegalArgumentException("Rule ID cannot be null or blank");
        }
        if (ruleName == null || ruleName.isBlank()) {
            throw new IllegalArgumentException("Rule name cannot be null or blank");
        }
        if (severityScore == null || severityScore < 0.0 || severityScore > 1.0) {
            throw new IllegalArgumentException("Severity score must be between 0.0 and 1.0");
        }
        if (transactionId == null || transactionId.isBlank()) {
            throw new IllegalArgumentException("Transaction ID cannot be null or blank");
        }
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("Customer ID cannot be null or blank");
        }
    }
    
    /**
     * Convenience method to determine if rule result indicates HIGH severity
     * Threshold: severityScore >= 0.75
     */
    public boolean isHighSeverity() {
        return this.severityScore >= 0.75;
    }
    
    /**
     * Convenience method to determine if rule result indicates MEDIUM severity
     * Threshold: 0.5 <= severityScore < 0.75
     */
    public boolean isMediumSeverity() {
        return this.severityScore >= 0.5 && this.severityScore < 0.75;
    }
    
    /**
     * Convenience method to determine if rule result indicates LOW severity
     * Threshold: severityScore < 0.5
     */
    public boolean isLowSeverity() {
        return this.severityScore < 0.5;
    }
    
    /**
     * Get evidence value by key with Optional wrapper
     * Provides safe access to rule-specific evidence attributes
     * 
     * @param key Evidence attribute key
     * @return Optional containing value if present
     */
    public Optional<Object> getEvidence(String key) {
        if (evidence == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(evidence.get(key));
    }
    
    /**
     * Builder pattern support for complex RuleResult construction
     * Chainable API for functional programming style
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Builder class for RuleResult construction
     * Simplifies creation of complex RuleResult objects
     */
    public static class Builder {
        private String ruleId;
        private String ruleName;
        private boolean triggered;
        private Double severityScore = 0.0;
        private String typology;
        private String riskCategoryId;
        private Long evaluationTimeMs = 0L;
        private String transactionId;
        private String customerId;
        private Map<String, Object> evidence;
        private String recommendedAction;
        private String regulatoryBaseline;
        private Instant evaluatedAt;
        
        public Builder ruleId(String ruleId) {
            this.ruleId = ruleId;
            return this;
        }
        
        public Builder ruleName(String ruleName) {
            this.ruleName = ruleName;
            return this;
        }
        
        public Builder triggered(boolean triggered) {
            this.triggered = triggered;
            return this;
        }
        
        public Builder severityScore(Double severityScore) {
            this.severityScore = severityScore;
            return this;
        }
        
        public Builder typology(String typology) {
            this.typology = typology;
            return this;
        }
        
        public Builder riskCategoryId(String riskCategoryId) {
            this.riskCategoryId = riskCategoryId;
            return this;
        }
        
        public Builder evaluationTimeMs(Long evaluationTimeMs) {
            this.evaluationTimeMs = evaluationTimeMs;
            return this;
        }
        
        public Builder transactionId(String transactionId) {
            this.transactionId = transactionId;
            return this;
        }
        
        public Builder customerId(String customerId) {
            this.customerId = customerId;
            return this;
        }
        
        public Builder evidence(Map<String, Object> evidence) {
            this.evidence = evidence;
            return this;
        }
        
        public Builder recommendedAction(String recommendedAction) {
            this.recommendedAction = recommendedAction;
            return this;
        }
        
        public Builder regulatoryBaseline(String regulatoryBaseline) {
            this.regulatoryBaseline = regulatoryBaseline;
            return this;
        }
        
        public Builder evaluatedAt(Instant evaluatedAt) {
            this.evaluatedAt = evaluatedAt;
            return this;
        }
        
        public RuleResult build() {
            return new RuleResult(
                ruleId, ruleName, triggered, severityScore, typology, riskCategoryId,
                evaluationTimeMs, transactionId, customerId, evidence,
                recommendedAction, regulatoryBaseline, evaluatedAt
            );
        }
    }
}
