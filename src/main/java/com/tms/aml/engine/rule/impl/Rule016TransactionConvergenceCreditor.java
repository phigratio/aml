package com.tms.aml.engine.rule.impl;

import com.tms.aml.domain.CustomerContext;
import com.tms.aml.domain.RuleResult;
import com.tms.aml.domain.Transaction;
import com.tms.aml.engine.rule.Rule;
import com.tms.aml.engine.rule.RuleContext;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RULE 016: Transaction Convergence - Creditor.
 */
public class Rule016TransactionConvergenceCreditor implements Rule {

    private static final String RULE_ID = "016";
    private static final String RULE_NAME = "Rule 016: Transaction Convergence - Creditor";
    private static final String TYPOLOGY = "Layering / Collusion / Accumulation of Funds";
    private static final String REGULATORY_BASIS =
        "Tazama Rule 016 - Transaction Convergence Creditor; FATF Red Flag Indicators; " +
        "Basel CDD Principles; Bank of Tanzania FIU AML Guidelines";
    private static final int RULE_PRIORITY = 86;

    private int windowHours = 24;
    private int uniqueDebtorThreshold = 8;
    private BigDecimal minimumWindowVolume = new BigDecimal("0.00");
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

        LocalDateTime windowStart = transaction.transactionDate().minusHours(windowHours);
        List<Transaction> inbound = context.transactionHistoryProvider().findTransactions(
            transaction.accountNumber(),
            windowStart,
            transaction.transactionDate(),
            Transaction.TransactionDirection.CREDIT
        );

        Set<String> uniqueDebtors = new LinkedHashSet<>();
        BigDecimal totalVolume = BigDecimal.ZERO;

        for (Transaction tx : inbound) {
            senderKey(tx).ifPresent(uniqueDebtors::add);
            totalVolume = totalVolume.add(tx.amount());
        }

        senderKey(transaction).ifPresent(uniqueDebtors::add);
        totalVolume = totalVolume.add(transaction.amount());

        int effectiveUniqueThreshold = applyRiskMultipliers
            ? adjustedUniqueThreshold(customer)
            : uniqueDebtorThreshold;

        BigDecimal effectiveMinVolume = applyRiskMultipliers
            ? adjustedMinimumVolume(customer)
            : minimumWindowVolume;

        int uniqueCount = uniqueDebtors.size();
        boolean triggered = uniqueCount >= effectiveUniqueThreshold
            && totalVolume.compareTo(effectiveMinVolume) >= 0;

        double deviationScore = triggered
            ? calculateDeviationScore(uniqueCount, effectiveUniqueThreshold, totalVolume, effectiveMinVolume)
            : 0.0;

        long evaluationTimeMs = System.currentTimeMillis() - start;

        Map<String, Object> evidence = Map.of(
            "time_window_hours", windowHours,
            "unique_sender_count", uniqueCount,
            "unique_sender_threshold", effectiveUniqueThreshold,
            "base_unique_sender_threshold", uniqueDebtorThreshold,
            "minimum_window_volume", effectiveMinVolume,
            "base_minimum_window_volume", minimumWindowVolume,
            "total_window_volume", totalVolume,
            "senders", List.copyOf(uniqueDebtors),
            "risk_multipliers_applied", applyRiskMultipliers,
            "deviation_score", deviationScore
        );

        String recommendedAction = triggered
            ? "Investigate incoming convergence from multiple debtors; validate sender relationships and source of funds"
            : "No immediate action. Continue monitoring incoming convergence behavior";

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

    public void setWindowHours(int windowHours) {
        this.windowHours = Math.max(1, windowHours);
    }

    public void setUniqueDebtorThreshold(int uniqueDebtorThreshold) {
        this.uniqueDebtorThreshold = Math.max(2, uniqueDebtorThreshold);
    }

    public void setMinimumWindowVolume(BigDecimal minimumWindowVolume) {
        if (minimumWindowVolume == null || minimumWindowVolume.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("minimumWindowVolume must be >= 0");
        }
        this.minimumWindowVolume = minimumWindowVolume;
    }

    public void setApplyRiskMultipliers(boolean applyRiskMultipliers) {
        this.applyRiskMultipliers = applyRiskMultipliers;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    private java.util.Optional<String> senderKey(Transaction transaction) {
        if (transaction.counterpartyAccount() != null && !transaction.counterpartyAccount().isBlank()) {
            return java.util.Optional.of("ACCOUNT:" + transaction.counterpartyAccount().trim());
        }
        if (transaction.counterpartyName() != null && !transaction.counterpartyName().isBlank()) {
            return java.util.Optional.of("NAME:" + transaction.counterpartyName().trim().toUpperCase());
        }
        return java.util.Optional.empty();
    }

    private int adjustedUniqueThreshold(CustomerContext customer) {
        return switch (customer.riskRating()) {
            case CRITICAL -> Math.max(2, uniqueDebtorThreshold - 3);
            case HIGH -> Math.max(2, uniqueDebtorThreshold - 2);
            case MEDIUM -> Math.max(2, uniqueDebtorThreshold - 1);
            case LOW -> uniqueDebtorThreshold;
        };
    }

    private BigDecimal adjustedMinimumVolume(CustomerContext customer) {
        return switch (customer.riskRating()) {
            case CRITICAL -> minimumWindowVolume.multiply(new BigDecimal("0.60"));
            case HIGH -> minimumWindowVolume.multiply(new BigDecimal("0.75"));
            case MEDIUM -> minimumWindowVolume.multiply(new BigDecimal("0.90"));
            case LOW -> minimumWindowVolume;
        };
    }

    private double calculateDeviationScore(
        int uniqueCount,
        int uniqueThreshold,
        BigDecimal totalVolume,
        BigDecimal minimumVolume
    ) {
        double uniqueRatio = uniqueThreshold <= 0 ? 1.0 : (double) uniqueCount / uniqueThreshold;
        double volumeRatio = minimumVolume.compareTo(BigDecimal.ZERO) == 0
            ? 1.0
            : totalVolume.divide(minimumVolume, 6, java.math.RoundingMode.HALF_UP).doubleValue();

        double uniqueFactor = Math.min(0.3, Math.max(0.0, uniqueRatio - 1.0) * 0.2);
        double volumeFactor = Math.min(0.2, Math.max(0.0, volumeRatio - 1.0) * 0.1);

        return Math.min(1.0, 0.5 + uniqueFactor + volumeFactor);
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
