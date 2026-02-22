package com.tms.aml.engine.rule.impl;

import com.tms.aml.domain.CustomerContext;
import com.tms.aml.domain.RuleResult;
import com.tms.aml.domain.Transaction;
import com.tms.aml.engine.rule.Rule;
import com.tms.aml.engine.rule.RuleContext;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * RULE 076: Time Since Last Transaction - Debtor.
 */
public class Rule076TimeSinceLastTransactionDebtor implements Rule {

    private static final String RULE_ID = "076";
    private static final String RULE_NAME = "Rule 076: Time Since Last Transaction - Debtor";
    private static final String TYPOLOGY = "Dormant Account Reactivation / Structuring / Burst Activity / Account Misuse";
    private static final String REGULATORY_BASIS =
        "Tazama Rule 076 pattern; FATF ongoing monitoring and dormant reactivation flags";
    private static final int RULE_PRIORITY = 65;

    private int inactivityThresholdDays = 90;
    private long burstThresholdMinutes = 60;
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
            return buildNotTriggered(transaction, customer, start, "Transaction direction is not DEBIT");
        }

        Optional<Transaction> latest = context.transactionHistoryProvider().findLatestTransaction(
            transaction.accountNumber(), transaction.transactionDate().minusNanos(1), Transaction.TransactionDirection.DEBIT
        );
        if (latest.isEmpty()) {
            return buildNotTriggered(transaction, customer, start, "No prior debtor transaction found");
        }

        Duration delta = Duration.between(latest.get().transactionDate(), transaction.transactionDate());
        if (delta.isNegative()) {
            return buildNotTriggered(transaction, customer, start, "Current transaction timestamp precedes last transaction");
        }

        boolean inactivity = delta.toDays() > inactivityThresholdDays;
        boolean burst = delta.toMinutes() < burstThresholdMinutes;
        boolean triggered = inactivity || burst;

        String pattern = inactivity ? "INACTIVITY_REACTIVATION" : (burst ? "BURST_ACTIVITY" : "NORMAL");
        double deviationScore = triggered
            ? inactivity
                ? Math.min(1.0, 0.5 + Math.min(0.5, (delta.toDays() - inactivityThresholdDays) / Math.max(1.0, inactivityThresholdDays)))
                : Math.min(1.0, 0.5 + Math.min(0.5, (burstThresholdMinutes - delta.toMinutes()) / Math.max(1.0, burstThresholdMinutes)))
            : 0.0;

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("last_transaction_at", latest.get().transactionDate());
        evidence.put("current_transaction_at", transaction.transactionDate());
        evidence.put("time_since_last_minutes", delta.toMinutes());
        evidence.put("time_since_last_days", delta.toDays());
        evidence.put("inactivity_threshold_days", inactivityThresholdDays);
        evidence.put("burst_threshold_minutes", burstThresholdMinutes);
        evidence.put("pattern_flag", pattern);
        evidence.put("deviation_score", deviationScore);

        return RuleResult.builder()
            .ruleId(RULE_ID)
            .ruleName(RULE_NAME)
            .triggered(triggered)
            .severityScore(deviationScore)
            .typology(TYPOLOGY)
            .riskCategoryId("RC001")
            .evaluationTimeMs(System.currentTimeMillis() - start)
            .transactionId(transaction.transactionId())
            .customerId(customer.customerId())
            .evidence(evidence)
            .recommendedAction(triggered
                ? "Investigate debtor activity gap/burst anomaly"
                : "No immediate action. Time gap between transactions is within normal range")
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

    public void setInactivityThresholdDays(int inactivityThresholdDays) {
        this.inactivityThresholdDays = Math.max(1, inactivityThresholdDays);
    }

    public void setBurstThresholdMinutes(long burstThresholdMinutes) {
        this.burstThresholdMinutes = Math.max(1, burstThresholdMinutes);
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    private RuleResult buildNotTriggered(Transaction tx, CustomerContext customer, long start, String reason) {
        return new RuleResult(
            RULE_ID, RULE_NAME, false, 0.0, TYPOLOGY, "RC001",
            System.currentTimeMillis() - start, tx.transactionId(), customer.customerId(),
            Map.of("reason_not_triggered", reason), "No action required", REGULATORY_BASIS, java.time.Instant.now()
        );
    }
}
