package com.tms.aml.engine.rule.impl;

import com.tms.aml.domain.CustomerContext;
import com.tms.aml.domain.RuleResult;
import com.tms.aml.domain.Transaction;
import com.tms.aml.engine.rule.Rule;
import com.tms.aml.engine.rule.RuleContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RULE 021: A Large Number of Similar Transaction Amounts - Creditor.
 */
public class Rule021LargeNumberSimilarTransactionAmountsCreditor implements Rule {

    private static final String RULE_ID = "021";
    private static final String RULE_NAME = "Rule 021: A Large Number of Similar Transaction Amounts - Creditor";
    private static final String TYPOLOGY = "Structuring / Smurfing / Layering";
    private static final String REGULATORY_BASIS =
        "Tazama Rule 021 - Similar transaction amounts creditor; FATF Red Flag Indicators; Basel CDD Principles";
    private static final int RULE_PRIORITY = 85;

    private int windowHours = 24;
    private int countThreshold = 8;
    private double tolerancePercent = 0.02;
    private BigDecimal minimumTotalIncomingVolume = new BigDecimal("0.00");
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
        List<Transaction> incoming = context.transactionHistoryProvider().findTransactions(
            transaction.accountNumber(),
            windowStart,
            transaction.transactionDate(),
            Transaction.TransactionDirection.CREDIT
        );

        List<Transaction> all = new ArrayList<>(incoming);
        all.add(transaction);

        BigDecimal totalVolume = all.stream()
            .map(Transaction::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal effectiveMinVolume = applyRiskMultipliers
            ? adjustedMinimumVolume(customer)
            : minimumTotalIncomingVolume;

        if (totalVolume.compareTo(effectiveMinVolume) < 0) {
            return buildNotTriggeredResult(transaction, customer, start, "Incoming volume below minimum threshold");
        }

        SimilarCluster maxCluster = findMaxCluster(all);

        int effectiveCountThreshold = applyRiskMultipliers
            ? adjustedCountThreshold(customer)
            : countThreshold;

        boolean triggered = maxCluster.transactions().size() >= effectiveCountThreshold;

        double deviationScore = triggered
            ? calculateDeviationScore(maxCluster.transactions().size(), effectiveCountThreshold, maxCluster.referenceAmount(), maxCluster.toleranceUsed())
            : 0.0;

        long evaluationTimeMs = System.currentTimeMillis() - start;

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("max_cluster_size", maxCluster.transactions().size());
        evidence.put("count_threshold", effectiveCountThreshold);
        evidence.put("base_count_threshold", countThreshold);
        evidence.put("cluster_reference_amount", maxCluster.referenceAmount());
        evidence.put("cluster_min_amount", maxCluster.minAmount());
        evidence.put("cluster_max_amount", maxCluster.maxAmount());
        evidence.put("tolerance_percent", tolerancePercent);
        evidence.put("tolerance_used", maxCluster.toleranceUsed());
        evidence.put("time_window_hours", windowHours);
        evidence.put("minimum_total_incoming_volume", effectiveMinVolume);
        evidence.put("base_minimum_total_incoming_volume", minimumTotalIncomingVolume);
        evidence.put("total_window_incoming_volume", totalVolume);
        evidence.put("cluster_transaction_ids", maxCluster.transactions().stream().map(Transaction::transactionId).toList());
        evidence.put("cluster_amounts", maxCluster.transactions().stream().map(Transaction::amount).toList());
        evidence.put("risk_multipliers_applied", applyRiskMultipliers);
        evidence.put("deviation_score", deviationScore);

        String recommendedAction = triggered
            ? "Investigate potential structuring via repeated similar incoming amounts"
            : "No immediate action. Continue monitoring incoming amount clustering";

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

    public void setCountThreshold(int countThreshold) {
        this.countThreshold = Math.max(2, countThreshold);
    }

    public void setTolerancePercent(double tolerancePercent) {
        this.tolerancePercent = Math.max(0.0, tolerancePercent);
    }

    public void setMinimumTotalIncomingVolume(BigDecimal minimumTotalIncomingVolume) {
        if (minimumTotalIncomingVolume == null || minimumTotalIncomingVolume.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("minimumTotalIncomingVolume must be >= 0");
        }
        this.minimumTotalIncomingVolume = minimumTotalIncomingVolume;
    }

    public void setApplyRiskMultipliers(boolean applyRiskMultipliers) {
        this.applyRiskMultipliers = applyRiskMultipliers;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    private SimilarCluster findMaxCluster(List<Transaction> transactions) {
        List<Transaction> sorted = transactions.stream()
            .filter(t -> t.amount() != null)
            .sorted(Comparator.comparing(Transaction::amount))
            .toList();

        SimilarCluster best = new SimilarCluster(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of());

        for (int i = 0; i < sorted.size(); i++) {
            Transaction anchor = sorted.get(i);
            BigDecimal reference = anchor.amount();
            BigDecimal tolerance = toleranceFor(reference);

            List<Transaction> cluster = new ArrayList<>();
            cluster.add(anchor);

            for (int j = i + 1; j < sorted.size(); j++) {
                Transaction candidate = sorted.get(j);
                BigDecimal diff = candidate.amount().subtract(reference).abs();
                if (diff.compareTo(tolerance) <= 0) {
                    cluster.add(candidate);
                } else {
                    break;
                }
            }

            if (cluster.size() > best.transactions().size()) {
                BigDecimal min = cluster.stream().map(Transaction::amount).min(BigDecimal::compareTo).orElse(reference);
                BigDecimal max = cluster.stream().map(Transaction::amount).max(BigDecimal::compareTo).orElse(reference);
                best = new SimilarCluster(reference, min, max, tolerance, List.copyOf(cluster));
            }
        }

        return best;
    }

    private BigDecimal toleranceFor(BigDecimal referenceAmount) {
        return referenceAmount.multiply(BigDecimal.valueOf(tolerancePercent)).setScale(4, RoundingMode.HALF_UP);
    }

    private int adjustedCountThreshold(CustomerContext customer) {
        return switch (customer.riskRating()) {
            case CRITICAL -> Math.max(2, countThreshold - 3);
            case HIGH -> Math.max(2, countThreshold - 2);
            case MEDIUM -> Math.max(2, countThreshold - 1);
            case LOW -> countThreshold;
        };
    }

    private BigDecimal adjustedMinimumVolume(CustomerContext customer) {
        return switch (customer.riskRating()) {
            case CRITICAL -> minimumTotalIncomingVolume.multiply(new BigDecimal("0.60"));
            case HIGH -> minimumTotalIncomingVolume.multiply(new BigDecimal("0.75"));
            case MEDIUM -> minimumTotalIncomingVolume.multiply(new BigDecimal("0.90"));
            case LOW -> minimumTotalIncomingVolume;
        };
    }

    private double calculateDeviationScore(
        int clusterSize,
        int threshold,
        BigDecimal reference,
        BigDecimal tolerance
    ) {
        double ratio = threshold <= 0 ? 1.0 : (double) clusterSize / threshold;
        double countFactor = Math.min(0.35, Math.max(0.0, ratio - 1.0) * 0.2);
        double amountFactor = reference.compareTo(BigDecimal.ZERO) > 0 && tolerance.compareTo(BigDecimal.ZERO) >= 0 ? 0.15 : 0.0;
        return Math.min(1.0, 0.5 + countFactor + amountFactor);
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

    private record SimilarCluster(
        BigDecimal referenceAmount,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        BigDecimal toleranceUsed,
        List<Transaction> transactions
    ) {}
}
