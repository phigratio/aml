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
 * RULE 018: Exceptionally Large Outgoing Transfer - Debtor.
 */
public class Rule018ExceptionallyLargeOutgoingTransferDebtor implements Rule {

    private static final String RULE_ID = "018";
    private static final String RULE_NAME = "Rule 018: Exceptionally Large Outgoing Transfer - Debtor";
    private static final String TYPOLOGY = "Layering / Fraud / Unusual Size";
    private static final String REGULATORY_BASIS =
        "Tazama Rule 018 - Exceptionally Large Outgoing Transfer Debtor; FATF Red Flag Indicators; Basel CDD Principles";
    private static final int RULE_PRIORITY = 88;

    private int historicalWindowDays = 30;
    private double thresholdMultiplier = 5.0;
    private int minimumHistoricalTransactions = 5;
    private BigDecimal absoluteMinimumAmountFloor = new BigDecimal("0.00");
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

        LocalDateTime windowStart = transaction.transactionDate().minusDays(historicalWindowDays);
        List<Transaction> outgoingHistory = context.transactionHistoryProvider().findTransactions(
            transaction.accountNumber(),
            windowStart,
            transaction.transactionDate().minusNanos(1),
            Transaction.TransactionDirection.DEBIT
        );

        int effectiveMinHistory = applyRiskMultipliers
            ? adjustedMinimumHistory(customer)
            : minimumHistoricalTransactions;

        if (outgoingHistory.size() < effectiveMinHistory) {
            return buildNotTriggeredResult(transaction, customer, start, "Insufficient historical outgoing transactions for baseline");
        }

        BigDecimal historicalAverage = outgoingHistory.stream()
            .map(Transaction::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal.valueOf(outgoingHistory.size()), 6, RoundingMode.HALF_UP);

        if (historicalAverage.compareTo(BigDecimal.ZERO) <= 0) {
            return buildNotTriggeredResult(transaction, customer, start, "Historical average outgoing amount is zero");
        }

        double effectiveMultiplier = applyRiskMultipliers
            ? adjustedMultiplier(customer)
            : thresholdMultiplier;

        BigDecimal effectiveFloor = applyRiskMultipliers
            ? adjustedAmountFloor(customer)
            : absoluteMinimumAmountFloor;

        BigDecimal thresholdAmount = historicalAverage.multiply(BigDecimal.valueOf(effectiveMultiplier));
        BigDecimal currentAmount = transaction.amount();

        boolean triggered = currentAmount.compareTo(thresholdAmount) > 0
            && currentAmount.compareTo(effectiveFloor) >= 0;

        BigDecimal ratio = currentAmount.divide(historicalAverage, 6, RoundingMode.HALF_UP);

        double deviationScore = triggered
            ? calculateDeviationScore(ratio.doubleValue(), effectiveMultiplier, currentAmount, effectiveFloor)
            : 0.0;

        long evaluationTimeMs = System.currentTimeMillis() - start;

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("current_amount", currentAmount);
        evidence.put("historical_average_amount", historicalAverage);
        evidence.put("ratio", ratio);
        evidence.put("threshold_multiplier", effectiveMultiplier);
        evidence.put("base_threshold_multiplier", thresholdMultiplier);
        evidence.put("threshold_amount", thresholdAmount);
        evidence.put("minimum_historical_transactions", effectiveMinHistory);
        evidence.put("base_minimum_historical_transactions", minimumHistoricalTransactions);
        evidence.put("absolute_amount_floor", effectiveFloor);
        evidence.put("base_absolute_amount_floor", absoluteMinimumAmountFloor);
        evidence.put("historical_window_days", historicalWindowDays);
        evidence.put("risk_multipliers_applied", applyRiskMultipliers);
        evidence.put("deviation_score", deviationScore);

        String recommendedAction = triggered
            ? "Investigate exceptionally large outgoing transfer against debtor baseline"
            : "No immediate action. Continue monitoring outgoing transfer size anomalies";

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

    public void setHistoricalWindowDays(int historicalWindowDays) {
        this.historicalWindowDays = Math.max(1, historicalWindowDays);
    }

    public void setThresholdMultiplier(double thresholdMultiplier) {
        this.thresholdMultiplier = Math.max(1.1, thresholdMultiplier);
    }

    public void setMinimumHistoricalTransactions(int minimumHistoricalTransactions) {
        this.minimumHistoricalTransactions = Math.max(1, minimumHistoricalTransactions);
    }

    public void setAbsoluteMinimumAmountFloor(BigDecimal absoluteMinimumAmountFloor) {
        if (absoluteMinimumAmountFloor == null || absoluteMinimumAmountFloor.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("absoluteMinimumAmountFloor must be >= 0");
        }
        this.absoluteMinimumAmountFloor = absoluteMinimumAmountFloor;
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

    private BigDecimal adjustedAmountFloor(CustomerContext customer) {
        return switch (customer.riskRating()) {
            case CRITICAL -> absoluteMinimumAmountFloor.multiply(new BigDecimal("0.70"));
            case HIGH -> absoluteMinimumAmountFloor.multiply(new BigDecimal("0.85"));
            case MEDIUM -> absoluteMinimumAmountFloor;
            case LOW -> absoluteMinimumAmountFloor;
        };
    }

    private double calculateDeviationScore(
        double observedRatio,
        double threshold,
        BigDecimal currentAmount,
        BigDecimal floorAmount
    ) {
        double ratioFactor = Math.min(0.35, Math.max(0.0, observedRatio - threshold) / Math.max(1.0, threshold));
        double floorFactor = floorAmount.compareTo(BigDecimal.ZERO) == 0
            ? 0.15
            : Math.min(0.15, Math.max(0.0,
                currentAmount.subtract(floorAmount).doubleValue() / Math.max(1.0, floorAmount.doubleValue())) * 0.05);

        return Math.min(1.0, 0.5 + ratioFactor + floorFactor);
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
