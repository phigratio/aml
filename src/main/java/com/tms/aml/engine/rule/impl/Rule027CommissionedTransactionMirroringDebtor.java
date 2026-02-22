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
 * RULE 027: Commissioned Transaction Mirroring - Debtor.
 */
public class Rule027CommissionedTransactionMirroringDebtor implements Rule {

    private static final String RULE_ID = "027";
    private static final String RULE_NAME = "Rule 027: Commissioned Transaction Mirroring - Debtor";
    private static final String TYPOLOGY = "Layering / Fee Abuse";
    private static final String REGULATORY_BASIS =
        "Tazama Rule 027 pattern; FATF suspicious transaction pattern indicators";
    private static final int RULE_PRIORITY = 75;

    private int windowHours = 48;
    private int matchThreshold = 3;
    private double amountTolerancePercent = 0.05;
    private double expectedCommissionPercent = 0.03;
    private double commissionTolerancePercent = 0.015;
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

        LocalDateTime windowStart = transaction.transactionDate().minusHours(windowHours);

        List<Transaction> incoming = context.transactionHistoryProvider().findTransactions(
            transaction.accountNumber(), windowStart, transaction.transactionDate(), Transaction.TransactionDirection.CREDIT
        );
        List<Transaction> outgoing = new ArrayList<>(context.transactionHistoryProvider().findTransactions(
            transaction.accountNumber(), windowStart, transaction.transactionDate(), Transaction.TransactionDirection.DEBIT
        ));
        outgoing.add(transaction);

        if (incoming.isEmpty()) {
            return buildNotTriggeredResult(transaction, customer, start, "No incoming flows available for commissioned mirroring");
        }

        List<CommissionedDebtorMirror> matches = findMatches(incoming, outgoing);
        int effectiveThreshold = applyRiskMultipliers ? adjustedThreshold(customer) : matchThreshold;

        boolean triggered = matches.size() >= effectiveThreshold;
        double feeConsistency = matches.isEmpty() ? 0.0 : feeConsistencyScore(matches);
        double deviationScore = triggered ? Math.min(1.0, 0.5 + Math.min(0.3, (matches.size() - effectiveThreshold) * 0.1) + feeConsistency * 0.2) : 0.0;

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("commissioned_mirror_match_count", matches.size());
        evidence.put("match_threshold", effectiveThreshold);
        evidence.put("base_match_threshold", matchThreshold);
        evidence.put("window_hours", windowHours);
        evidence.put("amount_tolerance_percent", amountTolerancePercent);
        evidence.put("expected_commission_percent", expectedCommissionPercent);
        evidence.put("commission_tolerance_percent", commissionTolerancePercent);
        evidence.put("fee_consistency_score", feeConsistency);
        evidence.put("matches", matches.stream().map(this::toEvidence).toList());
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
                ? "Investigate debtor commissioned mirroring and fee-abuse pattern"
                : "No immediate action. Continue monitoring commissioned debtor mirroring")
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

    public void setExpectedCommissionPercent(double expectedCommissionPercent) {
        this.expectedCommissionPercent = Math.max(0.0, expectedCommissionPercent);
    }

    public void setCommissionTolerancePercent(double commissionTolerancePercent) {
        this.commissionTolerancePercent = Math.max(0.0, commissionTolerancePercent);
    }

    public void setApplyRiskMultipliers(boolean applyRiskMultipliers) {
        this.applyRiskMultipliers = applyRiskMultipliers;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    private List<CommissionedDebtorMirror> findMatches(List<Transaction> incoming, List<Transaction> outgoing) {
        List<Transaction> sortedIncoming = incoming.stream().sorted(Comparator.comparing(Transaction::transactionDate)).toList();
        List<Transaction> sortedOutgoing = outgoing.stream().sorted(Comparator.comparing(Transaction::transactionDate)).toList();

        boolean[] usedIncoming = new boolean[sortedIncoming.size()];
        List<CommissionedDebtorMirror> matches = new ArrayList<>();

        for (Transaction out : sortedOutgoing) {
            int best = -1;
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
                if (Math.abs(commission - expectedCommissionPercent) > commissionTolerancePercent) {
                    continue;
                }

                BigDecimal expectedOut = in.amount().multiply(BigDecimal.valueOf(1.0 - expectedCommissionPercent));
                BigDecimal variance = relativeVariance(expectedOut, out.amount());
                if (variance.compareTo(BigDecimal.valueOf(amountTolerancePercent)) <= 0
                    && (bestVariance == null || variance.compareTo(bestVariance) < 0)) {
                    best = i;
                    bestVariance = variance;
                    bestCommission = commission;
                }
            }

            if (best >= 0) {
                usedIncoming[best] = true;
                matches.add(new CommissionedDebtorMirror(sortedIncoming.get(best), out, bestCommission, bestVariance));
            }
        }

        return matches;
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

    private double feeConsistencyScore(List<CommissionedDebtorMirror> matches) {
        if (matches.isEmpty()) {
            return 0.0;
        }
        List<Double> fees = matches.stream().map(CommissionedDebtorMirror::commissionPercent).toList();
        double mean = fees.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = fees.stream().mapToDouble(v -> Math.pow(v - mean, 2)).sum() / fees.size();
        return Math.max(0.0, 1.0 - Math.min(1.0, variance / Math.max(0.000001, commissionTolerancePercent * commissionTolerancePercent)));
    }

    private int adjustedThreshold(CustomerContext customer) {
        return switch (customer.riskRating()) {
            case CRITICAL -> Math.max(1, matchThreshold - 2);
            case HIGH -> Math.max(1, matchThreshold - 1);
            case MEDIUM -> matchThreshold;
            case LOW -> matchThreshold;
        };
    }

    private Map<String, Object> toEvidence(CommissionedDebtorMirror match) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("incoming_transaction_id", match.incoming().transactionId());
        m.put("outgoing_transaction_id", match.outgoing().transactionId());
        m.put("incoming_amount", match.incoming().amount());
        m.put("outgoing_amount", match.outgoing().amount());
        m.put("commission_percent", match.commissionPercent());
        m.put("variance_ratio", match.varianceRatio());
        m.put("incoming_timestamp", match.incoming().transactionDate());
        m.put("outgoing_timestamp", match.outgoing().transactionDate());
        return m;
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

    private record CommissionedDebtorMirror(
        Transaction incoming,
        Transaction outgoing,
        double commissionPercent,
        BigDecimal varianceRatio
    ) {}
}
