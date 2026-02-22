package com.tms.aml.engine.rule.impl;

import com.tms.aml.domain.CustomerContext;
import com.tms.aml.domain.RuleResult;
import com.tms.aml.domain.Transaction;
import com.tms.aml.engine.rule.Rule;
import com.tms.aml.engine.rule.RuleContext;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * RULE 003: Account Dormancy - Creditor.
 */
public class Rule003AccountDormancyCreditor implements Rule {

    private static final String RULE_ID = "003";
    private static final String RULE_NAME = "Rule 003: Account Dormancy - Creditor";
    private static final String TYPOLOGY = "Layering / Misuse";
    private static final String REGULATORY_BASIS =
        "Tazama Rule 003 - Account dormancy - creditor; FATF Red Flag Indicators; " +
        "Bank of Tanzania FIU AML Guidelines; FFIEC BSA/AML Appendix F";
    private static final int RULE_PRIORITY = 80;

    private int dormancyThresholdDays = 90;
    private BigDecimal amountThreshold = new BigDecimal("5000.00");
    private boolean enabled = true;

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
    public RuleResult evaluate(Transaction transaction, CustomerContext customer, RuleContext context)
        throws RuleEvaluationException {

        long start = System.currentTimeMillis();

        if (!transaction.isCredit()) {
            return buildNotTriggeredResult(transaction, customer, start, "Transaction direction is not CREDIT");
        }

        LocalDateTime lookupBefore = transaction.transactionDate().minusNanos(1);
        Optional<Transaction> latest = context.transactionHistoryProvider()
            .findLatestTransaction(transaction.accountNumber(), lookupBefore);

        if (latest.isEmpty()) {
            return buildNotTriggeredResult(transaction, customer, start, "No prior account activity found");
        }

        long inactivityDays = ChronoUnit.DAYS.between(
            latest.get().transactionDate(),
            transaction.transactionDate()
        );

        if (inactivityDays < 0) {
            throw new RuleEvaluationException(
                RULE_ID,
                transaction.transactionId(),
                "Historical transaction timestamp is after current transaction timestamp"
            );
        }

        boolean dormant = inactivityDays > dormancyThresholdDays;
        boolean amountExceeded = transaction.amount().compareTo(amountThreshold) > 0;
        boolean triggered = dormant && amountExceeded;

        double deviationScore = triggered
            ? calculateDeviationScore(inactivityDays, dormancyThresholdDays, transaction.amount(), amountThreshold)
            : 0.0;

        long evaluationTimeMs = System.currentTimeMillis() - start;

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("inactivity_period_days", inactivityDays);
        evidence.put("dormancy_threshold_days", dormancyThresholdDays);
        evidence.put("last_transaction_timestamp", latest.get().transactionDate());
        evidence.put("current_transaction_timestamp", transaction.transactionDate());
        evidence.put("transaction_amount", transaction.amount());
        evidence.put("amount_threshold", amountThreshold);
        evidence.put("is_dormant", dormant);
        evidence.put("is_amount_exceeded", amountExceeded);
        evidence.put("deviation_score", deviationScore);

        String recommendedAction = triggered
            ? "Investigate dormant-account reactivation. Validate source of funds and customer intent before allowing onward transfers"
            : "No immediate action. Continue monitoring account activity pattern";

        return RuleResult.builder()
            .ruleId(RULE_ID)
            .ruleName(RULE_NAME)
            .triggered(triggered)
            .severityScore(deviationScore)
            .typology(TYPOLOGY)
            .riskCategoryId("RC002")
            .evaluationTimeMs(evaluationTimeMs)
            .transactionId(transaction.transactionId())
            .customerId(customer.customerId())
            .evidence(evidence)
            .recommendedAction(recommendedAction)
            .regulatoryBaseline(REGULATORY_BASIS)
            .evaluatedAt(java.time.Instant.now())
            .build();
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public int getPriority() {
        return RULE_PRIORITY;
    }

    public void setDormancyThresholdDays(int dormancyThresholdDays) {
        this.dormancyThresholdDays = Math.max(1, dormancyThresholdDays);
    }

    public void setAmountThreshold(BigDecimal amountThreshold) {
        if (amountThreshold == null || amountThreshold.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amountThreshold must be positive");
        }
        this.amountThreshold = amountThreshold;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    private double calculateDeviationScore(
        long inactivityDays,
        int inactivityThreshold,
        BigDecimal txAmount,
        BigDecimal txAmountThreshold
    ) {
        double inactivityRatio = (double) inactivityDays / inactivityThreshold;
        double amountRatio = txAmount.divide(txAmountThreshold, 6, java.math.RoundingMode.HALF_UP).doubleValue();

        // Baseline score for trigger = 0.5; additional distance from thresholds raises score.
        double inactivityFactor = Math.min(0.3, Math.max(0.0, inactivityRatio - 1.0) * 0.15);
        double amountFactor = Math.min(0.2, Math.max(0.0, amountRatio - 1.0) * 0.1);

        return Math.min(1.0, 0.5 + inactivityFactor + amountFactor);
    }

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
            false,
            0.0,
            TYPOLOGY,
            "RC002",
            evaluationTimeMs,
            transaction.transactionId(),
            customer.customerId(),
            Map.of("reason_not_triggered", reason),
            "No action required",
            REGULATORY_BASIS,
            java.time.Instant.now()
        );
    }
}
