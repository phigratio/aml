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
 * RULE 025: Non-Commissioned Transaction Mirroring - Debtor.
 */
public class Rule025NonCommissionedTransactionMirroringDebtor implements Rule {

    private static final String RULE_ID = "025";
    private static final String RULE_NAME = "Rule 025: Non-Commissioned Transaction Mirroring - Debtor";
    private static final String TYPOLOGY = "Layering / Circular Funds Flow";
    private static final String REGULATORY_BASIS =
        "Tazama Rule 025 - Non-commissioned transaction mirroring debtor; FATF circular flow indicators";
    private static final int RULE_PRIORITY = 77;

    private int windowHours = 72;
    private int matchThreshold = 3;
    private double amountTolerancePercent = 0.05;
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
            transaction.accountNumber(),
            windowStart,
            transaction.transactionDate(),
            Transaction.TransactionDirection.CREDIT
        );

        List<Transaction> outgoing = new ArrayList<>(context.transactionHistoryProvider().findTransactions(
            transaction.accountNumber(),
            windowStart,
            transaction.transactionDate(),
            Transaction.TransactionDirection.DEBIT
        ));
        outgoing.add(transaction);

        if (incoming.isEmpty()) {
            return buildNotTriggeredResult(transaction, customer, start, "No incoming flows to compare in window");
        }

        List<MirroredMatch> mirroredMatches = findMirroredMatches(incoming, outgoing);

        int effectiveThreshold = applyRiskMultipliers
            ? adjustedMatchThreshold(customer)
            : matchThreshold;

        boolean triggered = mirroredMatches.size() >= effectiveThreshold;

        double deviationScore = triggered
            ? calculateDeviationScore(mirroredMatches.size(), effectiveThreshold)
            : 0.0;

        long evaluationTimeMs = System.currentTimeMillis() - start;

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("match_count", mirroredMatches.size());
        evidence.put("match_threshold", effectiveThreshold);
        evidence.put("base_match_threshold", matchThreshold);
        evidence.put("time_window_hours", windowHours);
        evidence.put("amount_tolerance_percent", amountTolerancePercent);
        evidence.put("incoming_count", incoming.size());
        evidence.put("outgoing_count", outgoing.size());
        evidence.put("mirrored_matches", mirroredMatches.stream().map(this::toEvidenceMap).toList());
        evidence.put("risk_multipliers_applied", applyRiskMultipliers);
        evidence.put("deviation_score", deviationScore);

        String recommendedAction = triggered
            ? "Investigate debtor pass-through/layering mirrored flows"
            : "No immediate action. Continue monitoring debtor mirroring patterns";

        return RuleResult.builder()
            .ruleId(RULE_ID)
            .ruleName(RULE_NAME)
            .triggered(triggered)
            .severityScore(deviationScore)
            .typology(TYPOLOGY)
            .riskCategoryId("RC004")
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

    public void setMatchThreshold(int matchThreshold) {
        this.matchThreshold = Math.max(1, matchThreshold);
    }

    public void setAmountTolerancePercent(double amountTolerancePercent) {
        this.amountTolerancePercent = Math.max(0.0, amountTolerancePercent);
    }

    public void setApplyRiskMultipliers(boolean applyRiskMultipliers) {
        this.applyRiskMultipliers = applyRiskMultipliers;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    private List<MirroredMatch> findMirroredMatches(List<Transaction> incoming, List<Transaction> outgoing) {
        List<Transaction> sortedIncoming = incoming.stream()
            .filter(t -> t.amount() != null)
            .sorted(Comparator.comparing(Transaction::transactionDate))
            .toList();
        List<Transaction> sortedOutgoing = outgoing.stream()
            .filter(t -> t.amount() != null)
            .sorted(Comparator.comparing(Transaction::transactionDate))
            .toList();

        boolean[] usedIncoming = new boolean[sortedIncoming.size()];
        List<MirroredMatch> matches = new ArrayList<>();

        for (Transaction out : sortedOutgoing) {
            int bestIndex = -1;
            BigDecimal bestVariance = null;

            for (int i = 0; i < sortedIncoming.size(); i++) {
                if (usedIncoming[i]) {
                    continue;
                }
                Transaction in = sortedIncoming.get(i);

                if (in.transactionDate().isAfter(out.transactionDate())) {
                    continue;
                }

                Duration gap = Duration.between(in.transactionDate(), out.transactionDate());
                if (gap.toHours() > windowHours) {
                    continue;
                }

                BigDecimal variance = relativeVariance(in.amount(), out.amount());
                if (variance.compareTo(BigDecimal.valueOf(amountTolerancePercent)) <= 0) {
                    if (bestVariance == null || variance.compareTo(bestVariance) < 0) {
                        bestVariance = variance;
                        bestIndex = i;
                    }
                }
            }

            if (bestIndex >= 0) {
                usedIncoming[bestIndex] = true;
                Transaction in = sortedIncoming.get(bestIndex);
                matches.add(new MirroredMatch(
                    in,
                    out,
                    relativeVariance(in.amount(), out.amount())
                ));
            }
        }

        return matches;
    }

    private BigDecimal relativeVariance(BigDecimal source, BigDecimal target) {
        if (source == null || source.compareTo(BigDecimal.ZERO) <= 0 || target == null) {
            return BigDecimal.ONE;
        }
        return source.subtract(target)
            .abs()
            .divide(source, 6, RoundingMode.HALF_UP);
    }

    private int adjustedMatchThreshold(CustomerContext customer) {
        return switch (customer.riskRating()) {
            case CRITICAL -> Math.max(1, matchThreshold - 2);
            case HIGH -> Math.max(1, matchThreshold - 1);
            case MEDIUM -> matchThreshold;
            case LOW -> matchThreshold;
        };
    }

    private double calculateDeviationScore(int observedMatches, int threshold) {
        double ratio = threshold <= 0 ? 1.0 : (double) observedMatches / threshold;
        double factor = Math.min(0.5, Math.max(0.0, ratio - 1.0) * 0.25);
        return Math.min(1.0, 0.5 + factor);
    }

    private Map<String, Object> toEvidenceMap(MirroredMatch match) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("incoming_transaction_id", match.incoming().transactionId());
        m.put("incoming_amount", match.incoming().amount());
        m.put("incoming_timestamp", match.incoming().transactionDate());
        m.put("incoming_counterparty", match.incoming().counterpartyAccount());
        m.put("outgoing_transaction_id", match.outgoing().transactionId());
        m.put("outgoing_amount", match.outgoing().amount());
        m.put("outgoing_timestamp", match.outgoing().transactionDate());
        m.put("outgoing_counterparty", match.outgoing().counterpartyAccount());
        m.put("variance_ratio", match.variance());
        return m;
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
            "RC004",
            evaluationTimeMs,
            transaction.transactionId(),
            customer.customerId(),
            Map.of("reason_not_triggered", reason),
            "No action required",
            REGULATORY_BASIS,
            java.time.Instant.now()
        );
    }

    private record MirroredMatch(
        Transaction incoming,
        Transaction outgoing,
        BigDecimal variance
    ) {}
}
