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
 * RULE 024: Non-Commissioned Transaction Mirroring - Creditor.
 */
public class Rule024NonCommissionedTransactionMirroringCreditor implements Rule {

    private static final String RULE_ID = "024";
    private static final String RULE_NAME = "Rule 024: Non-Commissioned Transaction Mirroring - Creditor";
    private static final String TYPOLOGY = "Layering / Circular Funds Flow / Pass-Through";
    private static final String REGULATORY_BASIS =
        "Tazama Rule 024 - Non-commissioned transaction mirroring creditor; FATF circular flow indicators";
    private static final int RULE_PRIORITY = 78;

    private int windowHours = 48;
    private int pairThreshold = 3;
    private double amountTolerancePercent = 0.05;
    private boolean excludeSameCounterparty = false;
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
            transaction.accountNumber(),
            windowStart,
            transaction.transactionDate(),
            Transaction.TransactionDirection.CREDIT
        ));
        List<Transaction> outgoing = new ArrayList<>(context.transactionHistoryProvider().findTransactions(
            transaction.accountNumber(),
            windowStart,
            transaction.transactionDate(),
            Transaction.TransactionDirection.DEBIT
        ));

        if (transaction.isCredit()) {
            incoming.add(transaction);
        } else {
            outgoing.add(transaction);
        }

        if (incoming.isEmpty() || outgoing.isEmpty()) {
            return buildNotTriggeredResult(transaction, customer, start, "Insufficient incoming/outgoing data in mirroring window");
        }

        List<MirroredPair> mirroredPairs = findMirroredPairs(incoming, outgoing);

        int effectivePairThreshold = applyRiskMultipliers
            ? adjustedPairThreshold(customer)
            : pairThreshold;

        boolean triggered = mirroredPairs.size() >= effectivePairThreshold;

        double deviationScore = triggered
            ? calculateDeviationScore(mirroredPairs.size(), effectivePairThreshold)
            : 0.0;

        long evaluationTimeMs = System.currentTimeMillis() - start;

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("mirrored_pair_count", mirroredPairs.size());
        evidence.put("pair_threshold", effectivePairThreshold);
        evidence.put("base_pair_threshold", pairThreshold);
        evidence.put("time_window_hours", windowHours);
        evidence.put("amount_tolerance_percent", amountTolerancePercent);
        evidence.put("exclude_same_counterparty", excludeSameCounterparty);
        evidence.put("incoming_count", incoming.size());
        evidence.put("outgoing_count", outgoing.size());
        evidence.put("mirrored_pairs", mirroredPairs.stream().map(this::toEvidenceMap).toList());
        evidence.put("risk_multipliers_applied", applyRiskMultipliers);
        evidence.put("deviation_score", deviationScore);

        String recommendedAction = triggered
            ? "Investigate potential pass-through/layering mirrored flows"
            : "No immediate action. Continue monitoring mirrored fund flows";

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

    public void setPairThreshold(int pairThreshold) {
        this.pairThreshold = Math.max(1, pairThreshold);
    }

    public void setAmountTolerancePercent(double amountTolerancePercent) {
        this.amountTolerancePercent = Math.max(0.0, amountTolerancePercent);
    }

    public void setExcludeSameCounterparty(boolean excludeSameCounterparty) {
        this.excludeSameCounterparty = excludeSameCounterparty;
    }

    public void setApplyRiskMultipliers(boolean applyRiskMultipliers) {
        this.applyRiskMultipliers = applyRiskMultipliers;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    private List<MirroredPair> findMirroredPairs(List<Transaction> incoming, List<Transaction> outgoing) {
        List<Transaction> sortedIncoming = incoming.stream()
            .filter(t -> t.amount() != null)
            .sorted(Comparator.comparing(Transaction::transactionDate))
            .toList();
        List<Transaction> sortedOutgoing = outgoing.stream()
            .filter(t -> t.amount() != null)
            .sorted(Comparator.comparing(Transaction::transactionDate))
            .toList();

        boolean[] usedIncoming = new boolean[sortedIncoming.size()];
        List<MirroredPair> pairs = new ArrayList<>();

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

                if (excludeSameCounterparty && sameCounterparty(in, out)) {
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
                pairs.add(new MirroredPair(
                    in,
                    out,
                    relativeVariance(in.amount(), out.amount())
                ));
            }
        }

        return pairs;
    }

    private boolean sameCounterparty(Transaction in, Transaction out) {
        String inParty = in.counterpartyAccount() == null ? "" : in.counterpartyAccount().trim();
        String outParty = out.counterpartyAccount() == null ? "" : out.counterpartyAccount().trim();
        return !inParty.isBlank() && inParty.equalsIgnoreCase(outParty);
    }

    private BigDecimal relativeVariance(BigDecimal source, BigDecimal target) {
        if (source == null || source.compareTo(BigDecimal.ZERO) <= 0 || target == null) {
            return BigDecimal.ONE;
        }
        return source.subtract(target)
            .abs()
            .divide(source, 6, RoundingMode.HALF_UP);
    }

    private int adjustedPairThreshold(CustomerContext customer) {
        return switch (customer.riskRating()) {
            case CRITICAL -> Math.max(1, pairThreshold - 2);
            case HIGH -> Math.max(1, pairThreshold - 1);
            case MEDIUM -> pairThreshold;
            case LOW -> pairThreshold;
        };
    }

    private double calculateDeviationScore(int observedPairs, int thresholdPairs) {
        double ratio = thresholdPairs <= 0 ? 1.0 : (double) observedPairs / thresholdPairs;
        double factor = Math.min(0.5, Math.max(0.0, ratio - 1.0) * 0.25);
        return Math.min(1.0, 0.5 + factor);
    }

    private Map<String, Object> toEvidenceMap(MirroredPair pair) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("incoming_transaction_id", pair.incoming().transactionId());
        m.put("incoming_amount", pair.incoming().amount());
        m.put("incoming_timestamp", pair.incoming().transactionDate());
        m.put("incoming_counterparty", pair.incoming().counterpartyAccount());
        m.put("outgoing_transaction_id", pair.outgoing().transactionId());
        m.put("outgoing_amount", pair.outgoing().amount());
        m.put("outgoing_timestamp", pair.outgoing().transactionDate());
        m.put("outgoing_counterparty", pair.outgoing().counterpartyAccount());
        m.put("variance_ratio", pair.variance());
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

    private record MirroredPair(
        Transaction incoming,
        Transaction outgoing,
        BigDecimal variance
    ) {}
}
