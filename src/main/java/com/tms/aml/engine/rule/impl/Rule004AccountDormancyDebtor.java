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
 * RULE 004: Account Dormancy - Debtor.
 */
public class Rule004AccountDormancyDebtor implements Rule {

    private static final String RULE_ID = "004";
    private static final String RULE_NAME = "Rule 004: Account Dormancy - Debtor";
    private static final String TYPOLOGY = "Structuring / Fund Concealment / Layering";
    private static final String REGULATORY_BASIS =
        "Tazama Rule 004 - Account dormancy - debtor; FATF Red Flag Indicators; " +
        "Bank of Tanzania FIU AML Guidelines; FFIEC BSA/AML Appendix F";
    private static final int RULE_PRIORITY = 82;

    private int dormancyThresholdDays = 90;
    private BigDecimal alertThreshold = new BigDecimal("5000.00");
    private boolean applyRiskMultipliers = true;
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

        if (!transaction.isDebit()) {
            return buildNotTriggeredResult(transaction, customer, start, "Transaction direction is not DEBIT");
        }

        LocalDateTime lookupBefore = transaction.transactionDate().minusNanos(1);
        Optional<Transaction> latestOutgoing = context.transactionHistoryProvider().findLatestTransaction(
            transaction.accountNumber(),
            lookupBefore,
            Transaction.TransactionDirection.DEBIT
        );

        if (latestOutgoing.isEmpty()) {
            return buildNotTriggeredResult(transaction, customer, start, "No prior outgoing account activity found");
        }

        long inactivityDays = ChronoUnit.DAYS.between(
            latestOutgoing.get().transactionDate(),
            transaction.transactionDate()
        );

        if (inactivityDays < 0) {
            throw new RuleEvaluationException(
                RULE_ID,
                transaction.transactionId(),
                "Historical outgoing transaction timestamp is after current transaction timestamp"
            );
        }

        int effectiveDormancyThreshold = applyRiskMultipliers
            ? adjustedDormancyThreshold(customer)
            : dormancyThresholdDays;

        BigDecimal effectiveAlertThreshold = applyRiskMultipliers
            ? adjustedAmountThreshold(customer)
            : alertThreshold;

        boolean dormant = inactivityDays > effectiveDormancyThreshold;
        boolean amountExceeded = transaction.amount().compareTo(effectiveAlertThreshold) > 0;
        boolean triggered = dormant && amountExceeded;

        double deviationScore = triggered
            ? calculateDeviationScore(inactivityDays, effectiveDormancyThreshold, transaction.amount(), effectiveAlertThreshold)
            : 0.0;

        long evaluationTimeMs = System.currentTimeMillis() - start;

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("inactivity_period_days", inactivityDays);
        evidence.put("dormancy_threshold_days", effectiveDormancyThreshold);
        evidence.put("base_dormancy_threshold_days", dormancyThresholdDays);
        evidence.put("last_outgoing_transaction_timestamp", latestOutgoing.get().transactionDate());
        evidence.put("current_transaction_timestamp", transaction.transactionDate());
        evidence.put("outgoing_transaction_amount", transaction.amount());
        evidence.put("alert_threshold", effectiveAlertThreshold);
        evidence.put("base_alert_threshold", alertThreshold);
        evidence.put("customer_risk_rating", customer.riskRating().name());
        evidence.put("risk_multipliers_applied", applyRiskMultipliers);
        evidence.put("is_dormant", dormant);
        evidence.put("is_amount_exceeded", amountExceeded);
        evidence.put("deviation_score", deviationScore);

        String recommendedAction = triggered
            ? "Investigate dormant-debtor reactivation. Validate purpose, beneficiaries, and source of funds before approving further debits"
            : "No immediate action. Continue monitoring outgoing activity pattern";

        return RuleResult.builder()
            .ruleId(RULE_ID)
            .ruleName(RULE_NAME)
            .triggered(triggered)
            .severityScore(deviationScore)
            .typology(TYPOLOGY)
            .riskCategoryId("RC003")
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

    public void setAlertThreshold(BigDecimal alertThreshold) {
        if (alertThreshold == null || alertThreshold.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("alertThreshold must be positive");
        }
        this.alertThreshold = alertThreshold;
    }

    public void setApplyRiskMultipliers(boolean applyRiskMultipliers) {
        this.applyRiskMultipliers = applyRiskMultipliers;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    private int adjustedDormancyThreshold(CustomerContext customer) {
        return switch (customer.riskRating()) {
            case CRITICAL -> Math.max(30, dormancyThresholdDays - 45);
            case HIGH -> Math.max(45, dormancyThresholdDays - 30);
            case MEDIUM -> Math.max(60, dormancyThresholdDays - 15);
            case LOW -> dormancyThresholdDays;
        };
    }

    private BigDecimal adjustedAmountThreshold(CustomerContext customer) {
        return switch (customer.riskRating()) {
            case CRITICAL -> alertThreshold.multiply(new BigDecimal("0.60"));
            case HIGH -> alertThreshold.multiply(new BigDecimal("0.70"));
            case MEDIUM -> alertThreshold.multiply(new BigDecimal("0.85"));
            case LOW -> alertThreshold;
        };
    }

    private double calculateDeviationScore(
        long inactivityDays,
        int inactivityThreshold,
        BigDecimal txAmount,
        BigDecimal txAmountThreshold
    ) {
        double inactivityRatio = (double) inactivityDays / inactivityThreshold;
        double amountRatio = txAmount.divide(txAmountThreshold, 6, java.math.RoundingMode.HALF_UP).doubleValue();

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
            "RC003",
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
