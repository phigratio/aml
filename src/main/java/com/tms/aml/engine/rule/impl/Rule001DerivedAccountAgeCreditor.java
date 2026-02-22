package com.tms.aml.engine.rule.impl;

import com.tms.aml.engine.rule.Rule;
import com.tms.aml.engine.rule.RuleContext;
import com.tms.aml.domain.Transaction;
import com.tms.aml.domain.CustomerContext;
import com.tms.aml.domain.RuleResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RULE 001: Derived Account Age – Creditor
 * 
 * ╔════════════════════════════════════════════════════════════════════════════╗
 * ║                    MONEY MULE & PLACEMENT DETECTION RULE                   ║
 * ║                                                                            ║
 * ║ Typology: PLACEMENT / LAYERING / MONEY MULE ACCOUNT                       ║
 * ║ Risk Level: HIGH (Priority: 90/100)                                        ║
 * ║ Regulatory Basis: FFIEC AML Manual (Appendix F), FATF Recommendation 3,4   ║
 * ║                   Tanzania FIU Guidelines, FinCEN                          ║
 * ╚════════════════════════════════════════════════════════════════════════════╝
 * 
 * BUSINESS LOGIC:
 * ===============
 * Newly opened accounts that suddenly receive large or unexpected CREDIT
 * (inbound) transactions are a well-established red flag for:
 * 
 *  1. MONEY MULE RECRUITMENT: Criminals recruit individuals to receive illicit
 *     funds via newly opened accounts, then transfer them out quickly
 *     
 *  2. PLACEMENT OF ILLICIT FUNDS: Initial step in placing illicit funds into
 *     the legitimate financial system
 *     
 *  3. LAYERING ACTIVITY: Obfuscating the origin of illicit funds through rapid
 *     movements in and out of newly created accounts
 * 
 * DETECTION FORMULA:
 * ==================
 * IF (
 *   account_age_days < AGE_THRESHOLD
 *   AND transaction_direction == CREDIT
 *   AND transaction_amount > AMOUNT_THRESHOLD  
 * )
 * THEN
 *   TRIGGER = TRUE
 *   severity_score = calculate_dynamic_severity(
 *       account_age,
 *       transaction_amount,
 *       customer_risk_rating,
 *       historical_activity
 *   )
 * END IF
 * 
 * CONFIGURABLE PARAMETERS:
 * ========================
 * 1. AGE_THRESHOLD (default: 30 days)
 *    - Defines "newly opened" account window
 *    - For LOW-RISK customers: may increase to 60-90 days
 *    - For HIGH-RISK customers: may decrease to 14-20 days
 *    - Configurable via application.yml / environment variables
 * 
 * 2. AMOUNT_THRESHOLD (default: configurable, example: 5,000 USD)
 *    - Absolute threshold for transaction amount
 *    - Should be configured per currency and jurisdiction
 *    - Can be adjusted based on customer segment (retail vs corporate)
 *    - Formula alternative: Based on % of historical monthly average
 * 
 * SEVERITY CALCULATION (Dynamic):
 * ===============================
 * Base Score = 0.5 (Rule triggered = "medium" severity)
 * 
 * Adjustments:
 *  + 0.15 if account age < 7 days (extremely new)
 *  + 0.15 if transaction > 3x baseline monthly avg (very unusual)
 *  + 0.15 if customer is HIGH-RISK or CRITICAL
 *  + 0.10 if customer is PEP or Sanctioned
 *  + 0.10 if multiple credit transactions within 24 hours (mule pattern)
 *  - 0.10 if transaction documented with clear business purpose
 * 
 * Final Score: capped at 0.0-1.0 range
 * 
 * INPUT REQUIREMENTS:
 * ===================
 *  From Transaction Record:
 *    - transactionDirection (must be CREDIT)
 *    - amount (transaction amount)
 *    - transactionDate (for age calculation)
 * 
 *  From CustomerContext Record:
 *    - accountOpenDate (CRITICAL - defines account age)
 *    - riskRating (affects severity calculation)
 *    - pep, sanctionedStatus (affects severity)
 *    - monthlyAverageCredit (for anomaly detection)
 * 
 * PERFORMANCE NOTES:
 * ==================
 * - Pure calculation-based rule (no database lookups)
 * - Target execution time: 2-3ms
 * - No external dependencies or network calls
 * - Thread-safe: All inputs are immutable records
 * 
 * @author AML Rules Team
 * @version 1.0
 * @since Java 25 (Virtual Threads / Structured Concurrency)
 */
public class Rule001DerivedAccountAgeCreditor implements Rule {
    
    // ═══════════════════════════════════════════════════════════════════════════
    // RULE METADATA
    // ═══════════════════════════════════════════════════════════════════════════
    
    private static final String RULE_ID = "001";
    private static final String RULE_NAME = "Rule 001: Derived Account Age – Creditor";
    private static final String TYPOLOGY = "Placement / Layering / Money Mule Account";
    private static final String REGULATORY_BASIS = 
        "FFIEC AML Manual (Appendix F) - Newly Opened Account Activity; " +
        "FATF Recommendations 3,4; " +
        "Tanzania FIU Guidelines; " +
        "FinCEN Advisory on Money Mules";
    private static final int RULE_PRIORITY = 90;
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIGURABLE THRESHOLDS (Injected from application.yml)
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Account age threshold in days. 
     * Accounts younger than this are considered "newly opened" and high-risk.
     * Default: 30 days (FFIEC standard)
     * Environment: AML_RULE_001_AGE_THRESHOLD_DAYS
     */
    private int ageThresholdDays = 30;
    
    /**
     * Minimum transaction amount to trigger rule (in base currency units).
     * Default: 5000 (assumes USD or similar major currency)
     * Environment: AML_RULE_001_AMOUNT_THRESHOLD
     */
    private BigDecimal amountThreshold = new BigDecimal("5000.00");
    
    /**
     * Whether rule is currently enabled for evaluation.
     * Allows operators to disable rule without redeployment.
     * Default: true
     * Environment: AML_RULE_001_ENABLED
     */
    private boolean enabled = true;
    
    /**
     * Whether to apply risk-based threshold adjustments.
     * If true: HIGH-RISK customers get more aggressive (lower) thresholds
     * If false: Same thresholds applied to all customer tiers
     * Default: true (recommended)
     */
    private boolean applyRiskBasedAdjustments = true;
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SEVERITY CALCULATION CONSTANTS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private static final double BASE_SEVERITY = 0.5;           // Rule triggered = 0.5
    private static final double VERY_NEW_PENALTY = 0.15;       // Age < 7 days
    private static final double UNUSUAL_AMOUNT_PENALTY = 0.15; // Amount > 3x baseline
    private static final double HIGH_RISK_PENALTY = 0.15;      // HIGH-RISK customer
    private static final double PEP_SANCTION_PENALTY = 0.10;   // PEP/Sanctioned
    private static final double MULTI_CREDIT_24H_PENALTY = 0.10; // Multiple inbound credits in 24h
    private static final double DOCUMENTATION_DISCOUNT = 0.10; // Clear purpose stated
    private static final int MULTI_CREDIT_LOOKBACK_HOURS = 24;
    
    // ═══════════════════════════════════════════════════════════════════════════
    // RULE INTERFACE IMPLEMENTATIONS
    // ═══════════════════════════════════════════════════════════════════════════
    
    @Override
    public String getRuleId() {
        return RULE_ID;
    }
    
    @Override
    public String getRuleName() {
        return RULE_NAME;
    }
    
    @Override
    public String getTypology() {
        return TYPOLOGY;
    }
    
    @Override
    public String getRegulatoryBasis() {
        return REGULATORY_BASIS;
    }
    
    @Override
    public boolean isEnabled() {
        return enabled;
    }
    
    @Override
    public int getPriority() {
        return RULE_PRIORITY;
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PRIMARY RULE EVALUATION METHOD (Called by Virtual Thread)
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * MAIN RULE EVALUATION LOGIC
     * 
     * Executes detection formula for Rule 001 in high-performance fashion.
     * This method is called by the RuleEngine via a Virtual Thread from
     * StructuredTaskScope, enabling concurrent evaluation of 40+ rules.
     * 
     * Execution Path Analysis:
     * 1. Early Exit 1: Transaction not CREDIT → return NOT_TRIGGERED
     * 2. Early Exit 2: Account not newly opened → return NOT_TRIGGERED
     * 3. Early Exit 3: Amount below threshold → return NOT_TRIGGERED
     * 4. Trigger Confirmed: Calculate dynamic severity score
     * 5. Build comprehensive evidence map
     * 6. Return RuleResult
     * 
     * @param transaction Transaction being evaluated (immutable)
     * @param customer Customer context data (immutable, cached)
     * @return RuleResult with trigger status and severity score
     * @throws Rule.RuleEvaluationException if critical data is missing
     */
    @Override
    public RuleResult evaluate(Transaction transaction, CustomerContext customer, RuleContext context)
        throws Rule.RuleEvaluationException {
        
        long evaluationStartTime = System.currentTimeMillis();
        
        try {
            // ───────────────────────────────────────────────────────────────────
            // STEP 1: Early Exit - Direction Filter (CREDIT only)
            // ───────────────────────────────────────────────────────────────────
            // Early filtering optimizes performance: most transactions are likely
            // DEBIT or do not match all conditions, so we fail fast.
            
            if (!transaction.isCredit()) {
                // Transaction is DEBIT or unknown direction - rule does not apply
                return buildNotTriggeredResult(
                    transaction, customer, 
                    evaluationStartTime,
                    "Transaction direction is not CREDIT"
                );
            }
            
            // ───────────────────────────────────────────────────────────────────
            // STEP 2: Calculate Account Age
            // ───────────────────────────────────────────────────────────────────
            // Critical calculation: determines if account is "newly opened"
            // Uses transaction date for consistency in batch processing
            
            LocalDate transactionDate = transaction.transactionDate().toLocalDate();
            long accountAgeDays = customer.accountAgeDays(transactionDate);
            
            // Validate that account was actually open on transaction date
            if (accountAgeDays < 0) {
                throw new Rule.RuleEvaluationException(
                    RULE_ID, transaction.transactionId(),
                    "Account open date is after transaction date - data integrity issue"
                );
            }
            
            // ───────────────────────────────────────────────────────────────────
            // STEP 3: Early Exit - Age Filter
            // ───────────────────────────────────────────────────────────────────
            
            int effectiveAgeThreshold = ageThresholdDays;
            
            // Apply risk-based adjustment to thresholds (optional)
            if (applyRiskBasedAdjustments && customer.isHighRiskOrCritical()) {
                // HIGH-RISK customers: use stricter (lower) age threshold
                // e.g., 30 days → 20 days for HIGH-RISK, → 14 days for CRITICAL
                effectiveAgeThreshold = switch (customer.riskRating()) {
                    case CRITICAL -> Math.max(14, ageThresholdDays - 16);
                    case HIGH -> Math.max(20, ageThresholdDays - 10);
                    case MEDIUM, LOW -> ageThresholdDays;
                };
            }
            
            if (accountAgeDays >= effectiveAgeThreshold) {
                // Account is not newly opened - rule does not apply
                return buildNotTriggeredResult(
                    transaction, customer,
                    evaluationStartTime,
                    String.format("Account age (%d days) exceeds threshold (%d days)", 
                                  accountAgeDays, effectiveAgeThreshold)
                );
            }
            
            // ───────────────────────────────────────────────────────────────────
            // STEP 4: Early Exit - Amount Filter
            // ───────────────────────────────────────────────────────────────────
            // Check: transaction amount > AMOUNT_THRESHOLD
            
            BigDecimal effectiveAmountThreshold = amountThreshold;
            
            if (!transaction.exceedsAmount(effectiveAmountThreshold)) {
                // Amount is below threshold - rule does not apply
                return buildNotTriggeredResult(
                    transaction, customer,
                    evaluationStartTime,
                    String.format("Transaction amount (%s) below threshold (%s)", 
                                  transaction.amount(), effectiveAmountThreshold)
                );
            }
            
            // ═══════════════════════════════════════════════════════════════════
            // ★ RULE TRIGGERED ★
            // All three conditions met: CREDIT + NEW + LARGE AMOUNT
            // ═══════════════════════════════════════════════════════════════════
            
            // ───────────────────────────────────────────────────────────────────
            // STEP 5: Calculate Dynamic Severity Score
            // ───────────────────────────────────────────────────────────────────
            
            double severityScore = calculateDynamicSeverity(
                transaction, customer, context, accountAgeDays
            );
            
            // ───────────────────────────────────────────────────────────────────
            // STEP 6: Build Evidence Map (for investigation)
            // ───────────────────────────────────────────────────────────────────
            
            Map<String, Object> evidence = buildEvidenceMap(
                transaction, customer, accountAgeDays, 
                effectiveAgeThreshold, effectiveAmountThreshold
            );
            
            // ───────────────────────────────────────────────────────────────────
            // STEP 7: Determine Recommended Action
            // ───────────────────────────────────────────────────────────────────
            
            String recommendedAction = determineRecommendedAction(
                severityScore, customer, accountAgeDays
            );
            
            // ───────────────────────────────────────────────────────────────────
            // STEP 8: Build and Return RuleResult
            // ───────────────────────────────────────────────────────────────────
            
            long evaluationTimeMs = System.currentTimeMillis() - evaluationStartTime;
            
            return RuleResult.builder()
                .ruleId(RULE_ID)
                .ruleName(RULE_NAME)
                .triggered(true)
                .severityScore(severityScore)
                .typology(TYPOLOGY)
                .riskCategoryId("RC004")  // Newly Opened Account Activity
                .evaluationTimeMs(evaluationTimeMs)
                .transactionId(transaction.transactionId())
                .customerId(customer.customerId())
                .evidence(evidence)
                .recommendedAction(recommendedAction)
                .regulatoryBaseline(REGULATORY_BASIS)
                .evaluatedAt(java.time.Instant.now())
                .build();
                
        } catch (Rule.RuleEvaluationException e) {
            // Re-throw evaluation exceptions (data integrity issues)
            throw e;
        } catch (Exception e) {
            // Unexpected errors - wrap and re-throw
            throw new Rule.RuleEvaluationException(
                RULE_ID, transaction.transactionId(),
                "Unexpected error during Rule 001 evaluation: " + e.getMessage(),
                e
            );
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SEVERITY CALCULATION LOGIC
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * DYNAMIC SEVERITY CALCULATION
     * 
     * Computes context-aware severity score based on:
     *  - How newly opened the account is
     *  - Magnitude of transaction vs. expected baseline
     *  - Customer risk profile
     *  - PEP/Sanctioned status
     * 
     * Scoring Model:
     *  Base: 0.50 (Rule triggered)
     *  +0.15: Account < 7 days old (extreme newness)
     *  +0.15: Amount > 3x monthly baseline (extreme anomaly)
     *  +0.15: HIGH/CRITICAL risk customer
     *  +0.10: PEP or Sanctioned
     *  -0.10: Clear business documentation (optional discount)
     *  Final: Capped at 0.0-1.0
     * 
     * @param transaction The transaction being evaluated
     * @param customer The customer profile
     * @param accountAgeDays Age of account in days
     * @return Normalized severity score 0.0-1.0
     */
    private double calculateDynamicSeverity(
        Transaction transaction,
        CustomerContext customer,
        RuleContext context,
        long accountAgeDays
    ) {
        double score = BASE_SEVERITY;
        
        // Penalty 1: Extreme newness (< 7 days)
        if (accountAgeDays < 7) {
            score += VERY_NEW_PENALTY;
        }
        
        // Penalty 2: Transaction significantly exceeds baseline activity
        if (customer.monthlyAverageCredit() != null &&
            customer.monthlyAverageCredit().compareTo(BigDecimal.ZERO) > 0) {
            
            BigDecimal baselineThreshold = customer.monthlyAverageCredit()
                .multiply(new BigDecimal("3.0"));
                
            if (transaction.amount().compareTo(baselineThreshold) > 0) {
                score += UNUSUAL_AMOUNT_PENALTY;
            }
        }
        
        // Penalty 3: Customer risk profile
        if (customer.isHighRiskOrCritical()) {
            score += HIGH_RISK_PENALTY;
        }
        
        // Penalty 4: PEP or Sanctioned status
        if (customer.pep() || customer.sanctionedStatus()) {
            score += PEP_SANCTION_PENALTY;
        }

        // Penalty 5: Multiple inbound credits to the same account in the last 24h
        if (hasMultipleRecentInboundCredits(transaction, context)) {
            score += MULTI_CREDIT_24H_PENALTY;
        }
        
        // Discount: Clear business purpose documented
        if (transaction.transactionPurpose() != null && 
            !transaction.transactionPurpose().isBlank() &&
            hasLegitimateBusinessPurpose(transaction.transactionPurpose())) {
            score -= DOCUMENTATION_DISCOUNT;
        }
        
        // Normalize to 0.0-1.0 range
        return Math.min(1.0, Math.max(0.0, score));
    }
    
    /**
     * Helper: Determine if transaction purpose indicates legitimate activity
     * Simple heuristic - can be enhanced with NLP or lookup tables
     * 
     * @param purpose Transaction purpose string
     * @return true if purpose suggests legitimate activity
     */
    private boolean hasLegitimateBusinessPurpose(String purpose) {
        String purposeLower = purpose.toLowerCase();
        
        // Keywords suggesting legitimate business purposes
        String[] legitimateKeywords = {
            "salary", "payroll", "salary payment",
            "freelance", "consulting", "contractor payment",
            "invoice", "payment for services",
            "business transaction", "goods purchase",
            "debt repayment", "loan repayment"
        };
        
        for (String keyword : legitimateKeywords) {
            if (purposeLower.contains(keyword)) {
                return true;
            }
        }
        
        return false;
    }

    private boolean hasMultipleRecentInboundCredits(Transaction transaction, RuleContext context) {
        LocalDateTime windowStart = transaction.transactionDate().minusHours(MULTI_CREDIT_LOOKBACK_HOURS);
        List<Transaction> history = context.transactionHistoryProvider().findInboundTransactions(
            transaction.accountNumber(),
            windowStart,
            transaction.transactionDate()
        );

        long distinctCredits = history.stream()
            .map(Transaction::transactionId)
            .distinct()
            .count();

        // Include current transaction because the provider may only have prior history.
        return distinctCredits + 1 >= 2;
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // EVIDENCE BUILDING
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Build comprehensive evidence map for investigator review
     * Contains all rule-relevant attributes that led to the trigger
     * 
     * @return Map of evidence key-value pairs
     */
    private Map<String, Object> buildEvidenceMap(
        Transaction transaction,
        CustomerContext customer,
        long accountAgeDays,
        int effectiveAgeThreshold,
        BigDecimal effectiveAmountThreshold
    ) {
        Map<String, Object> evidence = new HashMap<>();
        
        // Account Profiling Evidence
        evidence.put("account_open_date", customer.accountOpenDate());
        evidence.put("account_age_days", accountAgeDays);
        evidence.put("age_threshold_days", effectiveAgeThreshold);
        evidence.put("account_age_threshold_exceeded", false);
        
        // Transaction Evidence
        evidence.put("transaction_amount", transaction.amount());
        evidence.put("transaction_currency", transaction.currency());
        evidence.put("amount_threshold", effectiveAmountThreshold);
        evidence.put("transaction_direction", transaction.direction().name());
        evidence.put("transaction_date", transaction.transactionDate());
        
        // Risk Profiling Evidence
        evidence.put("customer_risk_rating", customer.riskRating().name());
        evidence.put("customer_type", customer.customerType().name());
        evidence.put("is_pep", customer.pep());
        evidence.put("is_sanctioned", customer.sanctionedStatus());
        evidence.put("jurisdiction", customer.jurisdiction());
        
        // Baseline Activity Evidence
        if (customer.monthlyAverageCredit() != null) {
            evidence.put("monthly_average_credit", customer.monthlyAverageCredit());
            if (customer.monthlyAverageCredit().compareTo(BigDecimal.ZERO) > 0) {
                evidence.put("transaction_vs_baseline_ratio",
                    transaction.amount().divide(customer.monthlyAverageCredit(),
                        java.math.RoundingMode.HALF_UP));
            } else {
                evidence.put("transaction_vs_baseline_ratio", null);
            }
        }
        
        // Counterparty Evidence
        evidence.put("counterparty_name", transaction.counterpartyName());
        evidence.put("counterparty_account", transaction.counterpartyAccount());
        evidence.put("transaction_purpose", transaction.transactionPurpose());
        
        // Rule Configuration Evidence (for audit trail)
        evidence.put("rule_config_age_threshold", ageThresholdDays);
        evidence.put("rule_config_amount_threshold", amountThreshold);
        evidence.put("rule_applied_risk_adjustments", applyRiskBasedAdjustments);
        
        return evidence;
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // RECOMMENDED ACTION DETERMINATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Determine recommended investigator action based on severity and context
     * 
     * @param severityScore Calculated severity (0.0-1.0)
     * @param customer Customer profile
     * @param accountAgeDays Account age in days
     * @return Recommended action description
     */
    private String determineRecommendedAction(
        double severityScore,
        CustomerContext customer,
        long accountAgeDays
    ) {
        if (severityScore >= 0.85) {
            return "IMMEDIATE INVESTIGATION: Extremely high-risk pattern. Consider: " +
                "(1) Block account pending KYC verification, " +
                "(2) File Suspicious Activity Report (SAR) immediately, " +
                "(3) Coordinate with fraud/compliance team";
        } else if (severityScore >= 0.75) {
            return "HIGH PRIORITY INVESTIGATION: High-risk newly opened account with large credit. " +
                "Recommend: (1) Enhanced verification of counterparty, " +
                "(2) Review historical transactions for this counterparty, " +
                "(3) Prepare preliminary SAR documentation";
        } else if (severityScore >= 0.60) {
            return "MEDIUM PRIORITY REVIEW: Newly opened account receiving material credit. " +
                "Conduct: (1) Enhanced transaction verification, " +
                "(2) Counterparty integrity check, " +
                "(3) Review for patterns with other transactions";
        } else {
            return "ROUTINE MONITORING: Newly opened account with large credit detected. " +
                "Action: (1) Document in customer file, (2) Monitor for additional patterns, " +
                "(3) Escalate if additional rules trigger";
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // NOT TRIGGERED RESULT BUILDER (Performance Optimization)
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Build efficiently a NOT-TRIGGERED result for early exits
     * Avoids unnecessary object creation in hot path
     * 
     * @param transaction Transaction evaluated
     * @param customer Customer profile
     * @param evaluationStartTime Start time of evaluation
     * @param reason Why rule was not triggered
     * @return RuleResult with triggered=false
     */
    private RuleResult buildNotTriggeredResult(
        Transaction transaction,
        CustomerContext customer,
        long evaluationStartTime,
        String reason
    ) {
        long evaluationTimeMs = System.currentTimeMillis() - evaluationStartTime;
        
        return new RuleResult(
            RULE_ID,
            RULE_NAME,
            false,  // triggered
            0.0,    // severityScore
            TYPOLOGY,
            "RC004",
            evaluationTimeMs,
            transaction.transactionId(),
            customer.customerId(),
            Map.of("reason_not_triggered", reason),
            "No action required",
            REGULATORY_BASIS,
            java.time.Instant.now()
        );
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIGURATION SETTERS (for Spring dependency injection)
    // ═══════════════════════════════════════════════════════════════════════════
    
    public void setAgeThresholdDays(int days) {
        this.ageThresholdDays = days;
    }
    
    public void setAmountThreshold(BigDecimal amount) {
        this.amountThreshold = amount;
    }
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    public void setApplyRiskBasedAdjustments(boolean apply) {
        this.applyRiskBasedAdjustments = apply;
    }
}
