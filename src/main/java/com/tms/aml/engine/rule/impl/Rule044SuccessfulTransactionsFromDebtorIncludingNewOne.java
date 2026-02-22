package com.tms.aml.engine.rule.impl;

import com.tms.aml.domain.CustomerContext;
import com.tms.aml.domain.RuleResult;
import com.tms.aml.domain.Transaction;
import com.tms.aml.engine.rule.Rule;
import com.tms.aml.engine.rule.RuleContext;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RULE 044: Successful Transactions from the Debtor, Including New One.
 */
public class Rule044SuccessfulTransactionsFromDebtorIncludingNewOne implements Rule {

    private static final String RULE_ID = "044";
    private static final String RULE_NAME = "Rule 044: Successful Transactions from the Debtor, Including New One";
    private static final String TYPOLOGY = "Increased Frequency / Velocity Anomalies";
    private static final String REGULATORY_BASIS =
        "Tazama Rule 044 pattern; FATF transaction frequency anomaly indicators";
    private static final int RULE_PRIORITY = 72;

    private int currentPeriodDays = 7;
    private int historicalPeriodDays = 30;
    private double countSpikeFactor = 3.0;
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

        LocalDateTime currentStart = transaction.transactionDate().minusDays(currentPeriodDays);
        LocalDateTime historicalStart = transaction.transactionDate().minusDays(historicalPeriodDays);
        LocalDateTime historicalEnd = currentStart.minusNanos(1);

        List<Transaction> currentTxs = context.transactionHistoryProvider().findTransactions(
            transaction.accountNumber(), currentStart, transaction.transactionDate(), Transaction.TransactionDirection.DEBIT
        );
        int currentCount = currentTxs.size() + 1;

        List<Transaction> historicalTxs = context.transactionHistoryProvider().findTransactions(
            transaction.accountNumber(), historicalStart, historicalEnd, Transaction.TransactionDirection.DEBIT
        );

        double periods = historicalPeriodDays <= currentPeriodDays
            ? 1.0
            : (double) historicalPeriodDays / currentPeriodDays;
        double historicalAvgCount = historicalTxs.size() / periods;

        if (historicalAvgCount <= 0.0) {
            return buildNotTriggeredResult(transaction, customer, start, "No historical baseline count available");
        }

        double effectiveFactor = applyRiskMultipliers ? adjustedFactor(customer) : countSpikeFactor;
        double ratio = currentCount / historicalAvgCount;
        boolean triggered = ratio > effectiveFactor;

        double deviationScore = triggered
            ? Math.min(1.0, 0.5 + Math.min(0.5, (ratio - effectiveFactor) / Math.max(1.0, effectiveFactor)))
            : 0.0;

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("current_outgoing_count", currentCount);
        evidence.put("historical_average_count", historicalAvgCount);
        evidence.put("count_ratio", ratio);
        evidence.put("count_spike_factor", effectiveFactor);
        evidence.put("base_count_spike_factor", countSpikeFactor);
        evidence.put("current_period_days", currentPeriodDays);
        evidence.put("historical_period_days", historicalPeriodDays);
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
                ? "Investigate sudden increase in debtor outgoing transaction frequency"
                : "No immediate action. Continue monitoring debtor transaction frequency")
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
        this.historicalPeriodDays = Math.max(this.currentPeriodDays + 1, historicalPeriodDays);
    }

    public void setCountSpikeFactor(double countSpikeFactor) {
        this.countSpikeFactor = Math.max(1.1, countSpikeFactor);
    }

    public void setApplyRiskMultipliers(boolean applyRiskMultipliers) {
        this.applyRiskMultipliers = applyRiskMultipliers;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    private double adjustedFactor(CustomerContext customer) {
        return switch (customer.riskRating()) {
            case CRITICAL -> Math.max(1.5, countSpikeFactor * 0.55);
            case HIGH -> Math.max(1.8, countSpikeFactor * 0.70);
            case MEDIUM -> Math.max(2.0, countSpikeFactor * 0.85);
            case LOW -> countSpikeFactor;
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
