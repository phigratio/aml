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
 * RULE 048: Large Transaction Amount vs History - Debtor.
 */
public class Rule048LargeTransactionAmountVsHistoryDebtor implements Rule {

    private static final String RULE_ID = "048";
    private static final String RULE_NAME = "Rule 048: Large Transaction Amount vs History - Debtor";
    private static final String TYPOLOGY = "Unusual Size / Layering";
    private static final String REGULATORY_BASIS =
        "Tazama Rule 048 pattern; FATF large transaction vs baseline indicators";
    private static final int RULE_PRIORITY = 70;

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

        if (!transaction.isDebit()) {
            return buildNotTriggeredResult(transaction, customer, start, "Transaction direction is not DEBIT");
        }

        LocalDateTime from = transaction.transactionDate().minusDays(historicalWindowDays);
        List<Transaction> history = context.transactionHistoryProvider().findTransactions(
            transaction.accountNumber(), from, transaction.transactionDate().minusNanos(1), Transaction.TransactionDirection.DEBIT
        );

        int effectiveMinHistory = applyRiskMultipliers ? adjustedMinHistory(customer) : minimumHistoricalTransactions;
        if (history.size() < effectiveMinHistory) {
            return buildNotTriggeredResult(transaction, customer, start, "Insufficient historical outgoing transactions");
        }

        BigDecimal avg = history.stream().map(Transaction::amount).reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal.valueOf(history.size()), 6, RoundingMode.HALF_UP);

        if (avg.compareTo(BigDecimal.ZERO) <= 0) {
            return buildNotTriggeredResult(transaction, customer, start, "Historical average is zero");
        }

        double effectiveMultiplier = applyRiskMultipliers ? adjustedMultiplier(customer) : thresholdMultiplier;
        BigDecimal thresholdAmount = avg.multiply(BigDecimal.valueOf(effectiveMultiplier));
        boolean triggered = transaction.amount().compareTo(thresholdAmount) > 0;

        BigDecimal ratio = transaction.amount().divide(avg, 6, RoundingMode.HALF_UP);
        double deviationScore = triggered
            ? Math.min(1.0, 0.5 + Math.min(0.5, Math.max(0.0, ratio.doubleValue() - effectiveMultiplier) / Math.max(1.0, effectiveMultiplier)))
            : 0.0;

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("current_outgoing_amount", transaction.amount());
        evidence.put("historical_mean_outgoing", avg);
        evidence.put("multiplier_ratio", ratio);
        evidence.put("threshold_multiplier", effectiveMultiplier);
        evidence.put("base_threshold_multiplier", thresholdMultiplier);
        evidence.put("threshold_amount", thresholdAmount);
        evidence.put("historical_window_days", historicalWindowDays);
        evidence.put("minimum_historical_transactions", effectiveMinHistory);
        evidence.put("base_minimum_historical_transactions", minimumHistoricalTransactions);
        evidence.put("risk_multipliers_applied", applyRiskMultipliers);
        evidence.put("deviation_score", deviationScore);

        return RuleResult.builder()
            .ruleId(RULE_ID)
            .ruleName(RULE_NAME)
            .triggered(triggered)
            .severityScore(deviationScore)
            .typology(TYPOLOGY)
            .riskCategoryId("RC003")
            .evaluationTimeMs(System.currentTimeMillis() - start)
            .transactionId(transaction.transactionId())
            .customerId(customer.customerId())
            .evidence(evidence)
            .recommendedAction(triggered
                ? "Investigate exceptionally large outgoing amount compared with debtor baseline"
                : "No immediate action. Continue monitoring outgoing amount outliers")
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

    private int adjustedMinHistory(CustomerContext customer) {
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

    private RuleResult buildNotTriggeredResult(Transaction transaction, CustomerContext customer, long start, String reason) {
        return new RuleResult(
            RULE_ID,
            RULE_NAME,
            false,
            0.0,
            TYPOLOGY,
            "RC003",
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
