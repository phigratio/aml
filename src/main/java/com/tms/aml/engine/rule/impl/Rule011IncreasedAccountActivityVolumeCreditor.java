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
 * RULE 011: Increased Account Activity - Volume (Creditor).
 */
public class Rule011IncreasedAccountActivityVolumeCreditor implements Rule {

    private static final String RULE_ID = "011";
    private static final String RULE_NAME = "Rule 011: Increased Account Activity - Volume (Creditor)";
    private static final String TYPOLOGY = "Layering / Placement / Bulk Deposit";
    private static final String REGULATORY_BASIS =
        "Tazama Rule 011 - Increased account activity: volume - creditor; FATF Red Flag Indicators; " +
        "Bank of Tanzania FIU AML Guidelines; Basel CDD Principles";
    private static final int RULE_PRIORITY = 83;

    private int currentPeriodDays = 7;
    private int historicalPeriodDays = 30;
    private double spikeFactor = 3.0;
    private int minimumCurrentPeriodTransactionCount = 3;
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

        LocalDateTime now = transaction.transactionDate();
        LocalDateTime currentWindowStart = now.minusDays(currentPeriodDays);
        LocalDateTime historicalWindowStart = currentWindowStart.minusDays(historicalPeriodDays);

        List<Transaction> historicalWindowTransactions = context.transactionHistoryProvider().findTransactions(
            transaction.accountNumber(),
            historicalWindowStart,
            currentWindowStart.minusNanos(1),
            Transaction.TransactionDirection.CREDIT
        );

        List<Transaction> currentWindowTransactions = context.transactionHistoryProvider().findTransactions(
            transaction.accountNumber(),
            currentWindowStart,
            now,
            Transaction.TransactionDirection.CREDIT
        );

        BigDecimal currentVolume = sumAmounts(currentWindowTransactions).add(transaction.amount());
        int currentCount = currentWindowTransactions.size() + 1;

        if (historicalWindowTransactions.isEmpty()) {
            return buildNotTriggeredResult(transaction, customer, start, "Insufficient historical incoming volume baseline");
        }

        BigDecimal historicalTotal = sumAmounts(historicalWindowTransactions);
        BigDecimal historicalAveragePerCurrentWindow = historicalTotal
            .divide(BigDecimal.valueOf(historicalPeriodDays), 6, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(currentPeriodDays));

        if (historicalAveragePerCurrentWindow.compareTo(BigDecimal.ZERO) <= 0) {
            return buildNotTriggeredResult(transaction, customer, start, "Historical baseline is zero");
        }

        double effectiveSpikeFactor = applyRiskMultipliers
            ? adjustedSpikeFactor(customer)
            : spikeFactor;

        int effectiveMinCurrentCount = applyRiskMultipliers
            ? adjustedMinCurrentCount(customer)
            : minimumCurrentPeriodTransactionCount;

        BigDecimal ratio = currentVolume.divide(historicalAveragePerCurrentWindow, 6, RoundingMode.HALF_UP);
        boolean triggered = ratio.compareTo(BigDecimal.valueOf(effectiveSpikeFactor)) > 0
            && currentCount >= effectiveMinCurrentCount;

        double deviationScore = triggered
            ? calculateDeviationScore(ratio.doubleValue(), effectiveSpikeFactor, currentCount, effectiveMinCurrentCount)
            : 0.0;

        long evaluationTimeMs = System.currentTimeMillis() - start;

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("current_incoming_volume", currentVolume);
        evidence.put("historical_incoming_volume_baseline", historicalAveragePerCurrentWindow);
        evidence.put("spike_ratio", ratio);
        evidence.put("spike_factor_threshold", effectiveSpikeFactor);
        evidence.put("base_spike_factor_threshold", spikeFactor);
        evidence.put("current_period_days", currentPeriodDays);
        evidence.put("historical_period_days", historicalPeriodDays);
        evidence.put("current_transaction_count", currentCount);
        evidence.put("min_current_period_transaction_count", effectiveMinCurrentCount);
        evidence.put("base_min_current_period_transaction_count", minimumCurrentPeriodTransactionCount);
        evidence.put("risk_multipliers_applied", applyRiskMultipliers);
        evidence.put("deviation_score", deviationScore);

        String recommendedAction = triggered
            ? "Investigate sudden incoming volume spike against account baseline; verify origin of funds and expected profile"
            : "No immediate action. Continue monitoring incoming volume trends";

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

    public void setCurrentPeriodDays(int currentPeriodDays) {
        this.currentPeriodDays = Math.max(1, currentPeriodDays);
    }

    public void setHistoricalPeriodDays(int historicalPeriodDays) {
        this.historicalPeriodDays = Math.max(2, historicalPeriodDays);
    }

    public void setSpikeFactor(double spikeFactor) {
        this.spikeFactor = Math.max(1.1, spikeFactor);
    }

    public void setMinimumCurrentPeriodTransactionCount(int minimumCurrentPeriodTransactionCount) {
        this.minimumCurrentPeriodTransactionCount = Math.max(1, minimumCurrentPeriodTransactionCount);
    }

    public void setApplyRiskMultipliers(boolean applyRiskMultipliers) {
        this.applyRiskMultipliers = applyRiskMultipliers;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    private BigDecimal sumAmounts(List<Transaction> transactions) {
        return transactions.stream()
            .map(Transaction::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private double adjustedSpikeFactor(CustomerContext customer) {
        return switch (customer.riskRating()) {
            case CRITICAL -> Math.max(1.5, spikeFactor * 0.55);
            case HIGH -> Math.max(1.8, spikeFactor * 0.65);
            case MEDIUM -> Math.max(2.2, spikeFactor * 0.80);
            case LOW -> spikeFactor;
        };
    }

    private int adjustedMinCurrentCount(CustomerContext customer) {
        return switch (customer.riskRating()) {
            case CRITICAL -> Math.max(1, minimumCurrentPeriodTransactionCount - 2);
            case HIGH -> Math.max(1, minimumCurrentPeriodTransactionCount - 1);
            case MEDIUM -> minimumCurrentPeriodTransactionCount;
            case LOW -> minimumCurrentPeriodTransactionCount;
        };
    }

    private double calculateDeviationScore(
        double observedRatio,
        double threshold,
        int observedCount,
        int minCount
    ) {
        double ratioFactor = Math.min(0.35, Math.max(0.0, observedRatio - threshold) / Math.max(1.0, threshold));
        double countFactor = minCount <= 0
            ? 0.15
            : Math.min(0.15, Math.max(0.0, (double) (observedCount - minCount) / minCount) * 0.1);

        return Math.min(1.0, 0.5 + ratioFactor + countFactor);
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
