package com.tms.aml.engine.rule.impl;

import com.tms.aml.domain.CustomerContext;
import com.tms.aml.domain.RuleResult;
import com.tms.aml.domain.Transaction;
import com.tms.aml.engine.rule.Rule;
import com.tms.aml.engine.rule.RuleContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RULE 020: Large Transaction Amount vs History - Creditor.
 */
public class Rule020LargeTransactionAmountVsHistoryCreditor implements Rule {

    private static final String RULE_ID = "020";
    private static final String RULE_NAME = "Rule 020: Large Transaction Amount vs History - Creditor";
    private static final String TYPOLOGY = "Layering / Unusual Inflow / Bulk Placement";
    private static final String REGULATORY_BASIS =
        "Tazama Rule 020 pattern; FATF Red Flag Indicators";
    private static final int RULE_PRIORITY = 87;

    private int historicalWindowDays = 30;
    private double thresholdMultiplier = 5.0;
    private int minimumHistoricalTransactions = 5;
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

        if (!transaction.isCredit()) {
            return buildNotTriggeredResult(transaction, customer, start, "Transaction direction is not CREDIT");
        }

        LocalDateTime windowStart = transaction.transactionDate().minusDays(historicalWindowDays);
        List<Transaction> incomingHistory = context.transactionHistoryProvider().findTransactions(
            transaction.accountNumber(),
            windowStart,
            transaction.transactionDate().minusNanos(1),
            Transaction.TransactionDirection.CREDIT
        );

        int effectiveMinHistory = applyRiskMultipliers
            ? adjustedMinimumHistory(customer)
            : minimumHistoricalTransactions;

        if (incomingHistory.size() < effectiveMinHistory) {
            return buildNotTriggeredResult(transaction, customer, start, "Insufficient historical incoming transactions for baseline");
        }

        BigDecimal historicalMean = incomingHistory.stream()
            .map(Transaction::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal.valueOf(incomingHistory.size()), 6, RoundingMode.HALF_UP);

        if (historicalMean.compareTo(BigDecimal.ZERO) <= 0) {
            return buildNotTriggeredResult(transaction, customer, start, "Historical incoming mean is zero");
        }

        double effectiveMultiplier = applyRiskMultipliers
            ? adjustedMultiplier(customer)
            : thresholdMultiplier;

        BigDecimal thresholdAmount = historicalMean.multiply(BigDecimal.valueOf(effectiveMultiplier));
        BigDecimal currentAmount = transaction.amount();
        boolean triggered = currentAmount.compareTo(thresholdAmount) > 0;

        BigDecimal ratio = currentAmount.divide(historicalMean, 6, RoundingMode.HALF_UP);
        double deviationScore = triggered
            ? calculateDeviationScore(ratio.doubleValue(), effectiveMultiplier)
            : 0.0;

        long evaluationTimeMs = System.currentTimeMillis() - start;

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("current_incoming_amount", currentAmount);
        evidence.put("historical_mean_incoming", historicalMean);
        evidence.put("multiplier_ratio", ratio);
        evidence.put("threshold_multiplier", effectiveMultiplier);
        evidence.put("base_threshold_multiplier", thresholdMultiplier);
        evidence.put("threshold_amount", thresholdAmount);
        evidence.put("historical_window_days", historicalWindowDays);
        evidence.put("minimum_historical_transactions", effectiveMinHistory);
        evidence.put("base_minimum_historical_transactions", minimumHistoricalTransactions);
        evidence.put("risk_multipliers_applied", applyRiskMultipliers);
        evidence.put("deviation_score", deviationScore);

        String recommendedAction = triggered
            ? "Investigate unusually large incoming transfer vs account history"
            : "No immediate action. Continue monitoring incoming amount anomalies";

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

    public void setHistoricalWindowDays(int historicalWindowDays) {
        this.historicalWindowDays = Math.max(1, historicalWindowDays);
    }

    public void setThresholdMultiplier(double thresholdMultiplier) {
        this.thresholdMultiplier = Math.max(1.1, thresholdMultiplier);
    }

    public void setMinimumHistoricalTransactions(int minimumHistoricalTransactions) {
        this.minimumHistoricalTransactions = Math.max(1, minimumHistoricalTransactions);
    }

    public void setApplyRiskMultipliers(boolean applyRiskMultipliers) {
        this.applyRiskMultipliers = applyRiskMultipliers;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    private int adjustedMinimumHistory(CustomerContext customer) {
        return switch (customer.riskRating()) {
            case CRITICAL -> Math.max(1, minimumHistoricalTransactions - 3);
            case HIGH -> Math.max(1, minimumHistoricalTransactions - 2);
            case MEDIUM -> Math.max(1, minimumHistoricalTransactions - 1);
            case LOW -> minimumHistoricalTransactions;
        };
    }

    private double adjustedMultiplier(CustomerContext customer) {
        return switch (customer.riskRating()) {
            case CRITICAL -> Math.max(2.0, thresholdMultiplier * 0.55);
            case HIGH -> Math.max(2.5, thresholdMultiplier * 0.65);
            case MEDIUM -> Math.max(3.0, thresholdMultiplier * 0.80);
            case LOW -> thresholdMultiplier;
        };
    }

    private double calculateDeviationScore(double observedRatio, double threshold) {
        double ratioFactor = Math.min(0.5, Math.max(0.0, observedRatio - threshold) / Math.max(1.0, threshold));
        return Math.min(1.0, 0.5 + ratioFactor);
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
