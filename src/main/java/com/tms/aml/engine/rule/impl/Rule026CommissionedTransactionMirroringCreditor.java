package com.tms.aml.engine.rule.impl;

import com.tms.aml.domain.CustomerContext;
import com.tms.aml.domain.RuleResult;
import com.tms.aml.domain.Transaction;
import com.tms.aml.engine.rule.Rule;
import com.tms.aml.engine.rule.RuleContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RULE 026: Commissioned Transaction Mirroring - Creditor.
 */
public class Rule026CommissionedTransactionMirroringCreditor implements Rule {

    private static final String RULE_ID = "026";
    private static final String RULE_NAME = "Rule 026: Commissioned Transaction Mirroring - Creditor";
    private static final String TYPOLOGY = "Layering / Abuse of Fee Structures / Circular Flows";
    private static final String REGULATORY_BASIS =
        "Tazama Rule 026 pattern; FATF recommendations on transaction metadata and suspicious patterns";
    private static final int RULE_PRIORITY = 76;

    private int windowHours = 48;
    private int matchThreshold = 3;
    private double amountTolerancePercent = 0.05;
    private double expectedCommissionMinPercent = 0.01;
    private double expectedCommissionMaxPercent = 0.05;
    private double maxCommissionVariance = 0.01;
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

        LocalDateTime windowStart = transaction.transactionDate().minusHours(windowHours);

        List<Transaction> incoming = new ArrayList<>(context.transactionHistoryProvider().findTransactions(
            transaction.accountNumber(), windowStart, transaction.transactionDate(), Transaction.TransactionDirection.CREDIT
        ));
        List<Transaction> outgoing = new ArrayList<>(context.transactionHistoryProvider().findTransactions(
            transaction.accountNumber(), windowStart, transaction.transactionDate(), Transaction.TransactionDirection.DEBIT
        ));

        if (transaction.isCredit()) {
            incoming.add(transaction);
        } else {
            outgoing.add(transaction);
        }

        if (incoming.isEmpty() || outgoing.isEmpty()) {
            return buildNotTriggeredResult(transaction, customer, start, "Insufficient incoming/outgoing data in window");
        }

        List<CommissionedMirrorPair> pairs = findPairs(incoming, outgoing);
        if (pairs.isEmpty()) {
            return buildNotTriggeredResult(transaction, customer, start, "No commissioned mirrored pairs found");
        }

        int effectiveThreshold = applyRiskMultipliers ? adjustedThreshold(customer) : matchThreshold;
        double commissionVariance = computeVariance(pairs.stream().map(CommissionedMirrorPair::commissionPercent).toList());
        boolean consistent = commissionVariance <= maxCommissionVariance;
        boolean triggered = pairs.size() >= effectiveThreshold && consistent;

        double deviationScore = triggered
            ? Math.min(1.0, 0.5 + Math.min(0.25, (pairs.size() - effectiveThreshold) * 0.08) + Math.min(0.25, (maxCommissionVariance - commissionVariance) * 8.0))
            : 0.0;

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("commissioned_mirror_match_count", pairs.size());
        evidence.put("match_threshold", effectiveThreshold);
        evidence.put("base_match_threshold", matchThreshold);
        evidence.put("time_window_hours", windowHours);
        evidence.put("amount_tolerance_percent", amountTolerancePercent);
        evidence.put("expected_commission_min_percent", expectedCommissionMinPercent);
        evidence.put("expected_commission_max_percent", expectedCommissionMaxPercent);
        evidence.put("commission_variance", commissionVariance);
        evidence.put("max_commission_variance", maxCommissionVariance);
        evidence.put("consistency_passed", consistent);
        evidence.put("matches", pairs.stream().map(this::toEvidence).toList());
        evidence.put("risk_multipliers_applied", applyRiskMultipliers);
        evidence.put("deviation_score", deviationScore);

        return RuleResult.builder()
            .ruleId(RULE_ID)
            .ruleName(RULE_NAME)
            .triggered(triggered)
            .severityScore(deviationScore)
            .typology(TYPOLOGY)
            .riskCategoryId("RC004")
            .evaluationTimeMs(System.currentTimeMillis() - start)
            .transactionId(transaction.transactionId())
            .customerId(customer.customerId())
            .evidence(evidence)
            .recommendedAction(triggered
                ? "Investigate commissioned mirroring consistency and potential fee-abuse layering"
                : "No immediate action. Continue monitoring commissioned mirroring patterns")
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

    public void setMatchThreshold(int matchThreshold) {
        this.matchThreshold = Math.max(1, matchThreshold);
    }

    public void setAmountTolerancePercent(double amountTolerancePercent) {
        this.amountTolerancePercent = Math.max(0.0, amountTolerancePercent);
    }

    public void setExpectedCommissionMinPercent(double expectedCommissionMinPercent) {
        this.expectedCommissionMinPercent = Math.max(0.0, expectedCommissionMinPercent);
    }

    public void setExpectedCommissionMaxPercent(double expectedCommissionMaxPercent) {
        this.expectedCommissionMaxPercent = Math.max(this.expectedCommissionMinPercent, expectedCommissionMaxPercent);
    }

    public void setMaxCommissionVariance(double maxCommissionVariance) {
        this.maxCommissionVariance = Math.max(0.0, maxCommissionVariance);
    }

    public void setApplyRiskMultipliers(boolean applyRiskMultipliers) {
        this.applyRiskMultipliers = applyRiskMultipliers;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    private List<CommissionedMirrorPair> findPairs(List<Transaction> incoming, List<Transaction> outgoing) {
        List<Transaction> sortedIncoming = incoming.stream()
            .sorted(Comparator.comparing(Transaction::transactionDate))
            .toList();
        List<Transaction> sortedOutgoing = outgoing.stream()
            .sorted(Comparator.comparing(Transaction::transactionDate))
            .toList();

        boolean[] usedIncoming = new boolean[sortedIncoming.size()];
        List<CommissionedMirrorPair> pairs = new ArrayList<>();

        for (Transaction out : sortedOutgoing) {
            int bestIndex = -1;
            BigDecimal bestVariance = null;
            double bestCommission = 0.0;

            for (int i = 0; i < sortedIncoming.size(); i++) {
                if (usedIncoming[i]) {
                    continue;
                }
                Transaction in = sortedIncoming.get(i);
                if (in.transactionDate().isAfter(out.transactionDate())) {
                    continue;
                }
                if (Duration.between(in.transactionDate(), out.transactionDate()).toHours() > windowHours) {
                    continue;
                }

                double commission = impliedCommissionPercent(in.amount(), out.amount());
                if (commission < expectedCommissionMinPercent || commission > expectedCommissionMaxPercent) {
                    continue;
                }

                BigDecimal expectedOut = in.amount().multiply(BigDecimal.valueOf(1.0 - commission));
                BigDecimal variance = relativeVariance(expectedOut, out.amount());
                if (variance.compareTo(BigDecimal.valueOf(amountTolerancePercent)) <= 0
                    && (bestVariance == null || variance.compareTo(bestVariance) < 0)) {
                    bestIndex = i;
                    bestVariance = variance;
                    bestCommission = commission;
                }
            }

            if (bestIndex >= 0) {
                usedIncoming[bestIndex] = true;
                Transaction in = sortedIncoming.get(bestIndex);
                pairs.add(new CommissionedMirrorPair(in, out, bestCommission, bestVariance));
            }
        }

        return pairs;
    }

    private double impliedCommissionPercent(BigDecimal incoming, BigDecimal outgoing) {
        if (incoming == null || outgoing == null || incoming.compareTo(BigDecimal.ZERO) <= 0) {
            return 1.0;
        }
        BigDecimal diff = incoming.subtract(outgoing);
        if (diff.compareTo(BigDecimal.ZERO) < 0) {
            return 1.0;
        }
        return diff.divide(incoming, 6, RoundingMode.HALF_UP).doubleValue();
    }

    private BigDecimal relativeVariance(BigDecimal baseline, BigDecimal observed) {
        if (baseline == null || observed == null || baseline.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ONE;
        }
        return baseline.subtract(observed).abs().divide(baseline, 6, RoundingMode.HALF_UP);
    }

    private double computeVariance(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        return values.stream().mapToDouble(v -> Math.pow(v - mean, 2)).sum() / values.size();
    }

    private int adjustedThreshold(CustomerContext customer) {
        return switch (customer.riskRating()) {
            case CRITICAL -> Math.max(1, matchThreshold - 2);
            case HIGH -> Math.max(1, matchThreshold - 1);
            case MEDIUM -> matchThreshold;
            case LOW -> matchThreshold;
        };
    }

    private Map<String, Object> toEvidence(CommissionedMirrorPair pair) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("incoming_transaction_id", pair.incoming().transactionId());
        map.put("outgoing_transaction_id", pair.outgoing().transactionId());
        map.put("incoming_amount", pair.incoming().amount());
        map.put("outgoing_amount", pair.outgoing().amount());
        map.put("commission_percent", pair.commissionPercent());
        map.put("variance_ratio", pair.varianceRatio());
        map.put("incoming_timestamp", pair.incoming().transactionDate());
        map.put("outgoing_timestamp", pair.outgoing().transactionDate());
        return map;
    }

    private RuleResult buildNotTriggeredResult(Transaction transaction, CustomerContext customer, long start, String reason) {
        return new RuleResult(
            RULE_ID,
            RULE_NAME,
            false,
            0.0,
            TYPOLOGY,
            "RC004",
            System.currentTimeMillis() - start,
            transaction.transactionId(),
            customer.customerId(),
            Map.of("reason_not_triggered", reason),
            "No action required",
            REGULATORY_BASIS,
            java.time.Instant.now()
        );
    }

    private record CommissionedMirrorPair(
        Transaction incoming,
        Transaction outgoing,
        double commissionPercent,
        BigDecimal varianceRatio
    ) {}
}
