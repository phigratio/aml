package com.tms.aml.engine.rule.impl;

import com.tms.aml.domain.CustomerContext;
import com.tms.aml.domain.RuleResult;
import com.tms.aml.domain.Transaction;
import com.tms.aml.engine.rule.Rule;
import com.tms.aml.engine.rule.RuleContext;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RULE 017: Transaction Divergence - Debtor.
 */
public class Rule017TransactionDivergenceDebtor implements Rule {

    private static final String RULE_ID = "017";
    private static final String RULE_NAME = "Rule 017: Transaction Divergence - Debtor";
    private static final String TYPOLOGY = "Structuring / Dispersal / Layering";
    private static final String REGULATORY_BASIS =
        "Tazama Rule 017 - Transaction Divergence Debtor; FATF Red Flag Indicators; Basel CDD Principles";
    private static final int RULE_PRIORITY = 82;

    private int timeWindowHours = 24;
    private int uniqueCreditorThreshold = 6;
    private int minimumOutgoingTransactionCount = 6;
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

        LocalDateTime windowStart = transaction.transactionDate().minusHours(timeWindowHours);
        List<Transaction> outgoing = context.transactionHistoryProvider().findTransactions(
            transaction.accountNumber(),
            windowStart,
            transaction.transactionDate(),
            Transaction.TransactionDirection.DEBIT
        );

        Set<String> uniqueCreditors = new LinkedHashSet<>();
        for (Transaction tx : outgoing) {
            creditorKey(tx).ifPresent(uniqueCreditors::add);
        }
        creditorKey(transaction).ifPresent(uniqueCreditors::add);

        int outgoingCount = outgoing.size() + 1;

        int effectiveCreditorThreshold = applyRiskMultipliers
            ? adjustedCreditorThreshold(customer)
            : uniqueCreditorThreshold;

        int effectiveMinimumTxCount = applyRiskMultipliers
            ? adjustedMinimumTxCount(customer)
            : minimumOutgoingTransactionCount;

        boolean triggered = uniqueCreditors.size() > effectiveCreditorThreshold
            && outgoingCount >= effectiveMinimumTxCount;

        double deviationScore = triggered
            ? calculateDeviationScore(uniqueCreditors.size(), effectiveCreditorThreshold, outgoingCount, effectiveMinimumTxCount)
            : 0.0;

        long evaluationTimeMs = System.currentTimeMillis() - start;

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("unique_receiver_count", uniqueCreditors.size());
        evidence.put("unique_receivers", List.copyOf(uniqueCreditors));
        evidence.put("unique_receiver_threshold", effectiveCreditorThreshold);
        evidence.put("base_unique_receiver_threshold", uniqueCreditorThreshold);
        evidence.put("outgoing_transaction_count", outgoingCount);
        evidence.put("minimum_outgoing_transaction_count", effectiveMinimumTxCount);
        evidence.put("base_minimum_outgoing_transaction_count", minimumOutgoingTransactionCount);
        evidence.put("time_window_hours", timeWindowHours);
        evidence.put("window_start", windowStart);
        evidence.put("window_end", transaction.transactionDate());
        evidence.put("risk_multipliers_applied", applyRiskMultipliers);
        evidence.put("deviation_score", deviationScore);

        String recommendedAction = triggered
            ? "Investigate rapid branching of outgoing transfers across multiple creditors"
            : "No immediate action. Continue monitoring divergence patterns";

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

    public void setTimeWindowHours(int timeWindowHours) {
        this.timeWindowHours = Math.max(1, timeWindowHours);
    }

    public void setUniqueCreditorThreshold(int uniqueCreditorThreshold) {
        this.uniqueCreditorThreshold = Math.max(2, uniqueCreditorThreshold);
    }

    public void setMinimumOutgoingTransactionCount(int minimumOutgoingTransactionCount) {
        this.minimumOutgoingTransactionCount = Math.max(1, minimumOutgoingTransactionCount);
    }

    public void setApplyRiskMultipliers(boolean applyRiskMultipliers) {
        this.applyRiskMultipliers = applyRiskMultipliers;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    private java.util.Optional<String> creditorKey(Transaction transaction) {
        if (transaction.counterpartyAccount() != null && !transaction.counterpartyAccount().isBlank()) {
            return java.util.Optional.of("ACCOUNT:" + transaction.counterpartyAccount().trim());
        }
        if (transaction.counterpartyName() != null && !transaction.counterpartyName().isBlank()) {
            return java.util.Optional.of("NAME:" + transaction.counterpartyName().trim().toUpperCase());
        }
        return java.util.Optional.empty();
    }

    private int adjustedCreditorThreshold(CustomerContext customer) {
        return switch (customer.riskRating()) {
            case CRITICAL -> Math.max(2, uniqueCreditorThreshold - 3);
            case HIGH -> Math.max(2, uniqueCreditorThreshold - 2);
            case MEDIUM -> Math.max(2, uniqueCreditorThreshold - 1);
            case LOW -> uniqueCreditorThreshold;
        };
    }

    private int adjustedMinimumTxCount(CustomerContext customer) {
        return switch (customer.riskRating()) {
            case CRITICAL -> Math.max(1, minimumOutgoingTransactionCount - 2);
            case HIGH -> Math.max(1, minimumOutgoingTransactionCount - 1);
            case MEDIUM -> minimumOutgoingTransactionCount;
            case LOW -> minimumOutgoingTransactionCount;
        };
    }

    private double calculateDeviationScore(
        int uniqueCount,
        int uniqueThreshold,
        int txCount,
        int minTxCount
    ) {
        double uniqueRatio = uniqueThreshold <= 0 ? 1.0 : (double) uniqueCount / uniqueThreshold;
        double txRatio = minTxCount <= 0 ? 1.0 : (double) txCount / minTxCount;

        double uniqueFactor = Math.min(0.30, Math.max(0.0, uniqueRatio - 1.0) * 0.2);
        double txFactor = Math.min(0.20, Math.max(0.0, txRatio - 1.0) * 0.1);

        return Math.min(1.0, 0.5 + uniqueFactor + txFactor);
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
