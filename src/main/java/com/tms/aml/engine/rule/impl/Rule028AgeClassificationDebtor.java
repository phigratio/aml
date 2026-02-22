package com.tms.aml.engine.rule.impl;

import com.tms.aml.domain.CustomerContext;
import com.tms.aml.domain.RuleResult;
import com.tms.aml.domain.Transaction;
import com.tms.aml.engine.rule.Rule;
import com.tms.aml.engine.rule.RuleContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RULE 028: Age Classification - Debtor.
 */
public class Rule028AgeClassificationDebtor implements Rule {

    private static final String RULE_ID = "028";
    private static final String RULE_NAME = "Rule 028: Age Classification - Debtor";
    private static final String TYPOLOGY = "New Account Exploitation / Placement / Fraud";
    private static final String REGULATORY_BASIS =
        "Tazama Rule 028 pattern; FATF unusual activity in new accounts";
    private static final int RULE_PRIORITY = 74;

    private int ageThresholdDays = 30;
    private int velocityWindowHours = 24;
    private int minimumTransactionCount = 3;
    private double volumeSpikeFactor = 3.0;
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
    public RuleResult evaluate(Transaction transaction, CustomerContext customer, RuleContext context) {
        long start = System.currentTimeMillis();

        if (!transaction.isDebit()) {
            return buildNotTriggeredResult(transaction, customer, start, "Transaction direction is not DEBIT");
        }

        long accountAgeDays = customer.accountAgeDays(transaction.transactionDate().toLocalDate());
        int effectiveAgeThreshold = applyRiskMultipliers ? adjustedAgeThreshold(customer) : ageThresholdDays;

        if (accountAgeDays < 0 || accountAgeDays >= effectiveAgeThreshold) {
            return buildNotTriggeredResult(transaction, customer, start, "Account is not newly opened for debtor-age rule");
        }

        LocalDateTime windowStart = transaction.transactionDate().minusHours(velocityWindowHours);
        List<Transaction> recentOutgoing = context.transactionHistoryProvider().findTransactions(
            transaction.accountNumber(), windowStart, transaction.transactionDate(), Transaction.TransactionDirection.DEBIT
        );

        int txCount = recentOutgoing.size() + 1;
        BigDecimal currentWindowVolume = recentOutgoing.stream()
            .map(Transaction::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .add(transaction.amount());

        BigDecimal baseline = customer.monthlyAverageDebit() == null
            ? BigDecimal.ZERO
            : customer.monthlyAverageDebit().divide(BigDecimal.valueOf(30), 6, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(Math.max(1.0, velocityWindowHours / 24.0)));

        double volumeRatio = baseline.compareTo(BigDecimal.ZERO) <= 0
            ? (currentWindowVolume.compareTo(BigDecimal.ZERO) > 0 ? Double.POSITIVE_INFINITY : 1.0)
            : currentWindowVolume.divide(baseline, 6, java.math.RoundingMode.HALF_UP).doubleValue();

        int effectiveMinTx = applyRiskMultipliers ? adjustedMinimumTx(customer) : minimumTransactionCount;
        boolean unusualPattern = txCount >= effectiveMinTx && volumeRatio >= volumeSpikeFactor;

        double deviationScore = unusualPattern
            ? Math.min(1.0, 0.5 + Math.min(0.2, ((double) (effectiveAgeThreshold - accountAgeDays) / Math.max(1, effectiveAgeThreshold)) * 0.2)
                + Math.min(0.3, Math.max(0.0, volumeRatio - volumeSpikeFactor) * 0.1))
            : 0.0;

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("account_age_days", accountAgeDays);
        evidence.put("age_threshold_days", effectiveAgeThreshold);
        evidence.put("base_age_threshold_days", ageThresholdDays);
        evidence.put("velocity_window_hours", velocityWindowHours);
        evidence.put("recent_outgoing_count", txCount);
        evidence.put("minimum_transaction_count", effectiveMinTx);
        evidence.put("base_minimum_transaction_count", minimumTransactionCount);
        evidence.put("current_window_volume", currentWindowVolume);
        evidence.put("baseline_volume", baseline);
        evidence.put("volume_ratio", volumeRatio);
        evidence.put("volume_spike_factor", volumeSpikeFactor);
        evidence.put("pattern_deviation_score", deviationScore);
        evidence.put("risk_multipliers_applied", applyRiskMultipliers);

        return RuleResult.builder()
            .ruleId(RULE_ID)
            .ruleName(RULE_NAME)
            .triggered(unusualPattern)
            .severityScore(deviationScore)
            .typology(TYPOLOGY)
            .riskCategoryId("RC001")
            .evaluationTimeMs(System.currentTimeMillis() - start)
            .transactionId(transaction.transactionId())
            .customerId(customer.customerId())
            .evidence(evidence)
            .recommendedAction(unusualPattern
                ? "Investigate unusual debit activity from a newly opened account"
                : "No immediate action. Continue monitoring new-account debit behavior")
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

    public void setAgeThresholdDays(int ageThresholdDays) {
        this.ageThresholdDays = Math.max(1, ageThresholdDays);
    }

    public void setVelocityWindowHours(int velocityWindowHours) {
        this.velocityWindowHours = Math.max(1, velocityWindowHours);
    }

    public void setMinimumTransactionCount(int minimumTransactionCount) {
        this.minimumTransactionCount = Math.max(1, minimumTransactionCount);
    }

    public void setVolumeSpikeFactor(double volumeSpikeFactor) {
        this.volumeSpikeFactor = Math.max(1.0, volumeSpikeFactor);
    }

    public void setApplyRiskMultipliers(boolean applyRiskMultipliers) {
        this.applyRiskMultipliers = applyRiskMultipliers;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    private int adjustedAgeThreshold(CustomerContext customer) {
        return switch (customer.riskRating()) {
            case CRITICAL -> Math.max(7, ageThresholdDays - 14);
            case HIGH -> Math.max(14, ageThresholdDays - 7);
            case MEDIUM -> ageThresholdDays;
            case LOW -> ageThresholdDays;
        };
    }

    private int adjustedMinimumTx(CustomerContext customer) {
        return switch (customer.riskRating()) {
            case CRITICAL -> Math.max(1, minimumTransactionCount - 2);
            case HIGH -> Math.max(1, minimumTransactionCount - 1);
            case MEDIUM -> minimumTransactionCount;
            case LOW -> minimumTransactionCount;
        };
    }

    private RuleResult buildNotTriggeredResult(Transaction transaction, CustomerContext customer, long start, String reason) {
        return new RuleResult(
            RULE_ID,
            RULE_NAME,
            false,
            0.0,
            TYPOLOGY,
            "RC001",
            System.currentTimeMillis() - start,
            transaction.transactionId(),
            customer.customerId(),
            Map.of("reason_not_triggered", reason),
            "No action required",
            REGULATORY_BASIS,
            java.time.Instant.now()
        );
    }
}
