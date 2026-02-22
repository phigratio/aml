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
 * RULE 006: Outgoing Transfer Similarity - Amounts.
 */
public class Rule006OutgoingTransferSimilarityAmounts implements Rule {

    private static final String RULE_ID = "006";
    private static final String RULE_NAME = "Rule 006: Outgoing Transfer Similarity - Amounts";
    private static final String TYPOLOGY = "Structuring (Smurfing) / Layering";
    private static final String REGULATORY_BASIS =
        "Tazama Rule 006 - Outgoing transfer similarity - amounts; FATF Red Flag Indicators; " +
        "Bank of Tanzania FIU AML Guidelines; AUSTRAC Transaction Monitoring Guidance";
    private static final int RULE_PRIORITY = 84;

    private int observationWindowHours = 24;
    private int similarityCountThreshold = 5;
    private double tolerancePercent = 0.01;
    private BigDecimal fixedToleranceAmount = new BigDecimal("1000.00");
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

        LocalDateTime windowStart = transaction.transactionDate().minusHours(observationWindowHours);
        List<Transaction> historicalOutgoing = context.transactionHistoryProvider().findTransactions(
            transaction.accountNumber(),
            windowStart,
            transaction.transactionDate(),
            Transaction.TransactionDirection.DEBIT
        );

        List<Transaction> candidates = new ArrayList<>(historicalOutgoing);
        candidates.add(transaction);

        if (candidates.isEmpty()) {
            return buildNotTriggeredResult(transaction, customer, start, "No outgoing transactions in observation window");
        }

        int effectiveThreshold = applyRiskMultipliers
            ? adjustedCountThreshold(customer)
            : similarityCountThreshold;

        SimilarGroup bestGroup = findLargestSimilarAmountGroup(candidates);
        int groupCount = bestGroup.transactions().size();
        boolean triggered = groupCount >= effectiveThreshold;

        double deviationScore = triggered
            ? calculateDeviationScore(groupCount, effectiveThreshold, bestGroup.referenceAmount())
            : 0.0;

        long evaluationTimeMs = System.currentTimeMillis() - start;

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("time_window_hours", observationWindowHours);
        evidence.put("similarity_count_threshold", effectiveThreshold);
        evidence.put("base_similarity_count_threshold", similarityCountThreshold);
        evidence.put("tolerance_percent", tolerancePercent);
        evidence.put("fixed_tolerance_amount", fixedToleranceAmount);
        evidence.put("risk_multipliers_applied", applyRiskMultipliers);
        evidence.put("reference_amount", bestGroup.referenceAmount());
        evidence.put("tolerance_used", bestGroup.toleranceUsed());
        evidence.put("similar_group_count", groupCount);
        evidence.put("similar_group_amounts", bestGroup.transactions().stream().map(Transaction::amount).toList());
        evidence.put("similar_group_transaction_ids", bestGroup.transactions().stream().map(Transaction::transactionId).toList());
        evidence.put("deviation_score", deviationScore);

        String recommendedAction = triggered
            ? "Investigate possible structuring via near-identical outgoing amounts; review beneficiary network and threshold-evasion behavior"
            : "No immediate action. Continue monitoring outgoing amount patterns";

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

    public void setObservationWindowHours(int observationWindowHours) {
        this.observationWindowHours = Math.max(1, observationWindowHours);
    }

    public void setSimilarityCountThreshold(int similarityCountThreshold) {
        this.similarityCountThreshold = Math.max(2, similarityCountThreshold);
    }

    public void setTolerancePercent(double tolerancePercent) {
        this.tolerancePercent = Math.max(0.0, tolerancePercent);
    }

    public void setFixedToleranceAmount(BigDecimal fixedToleranceAmount) {
        if (fixedToleranceAmount == null || fixedToleranceAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("fixedToleranceAmount must be >= 0");
        }
        this.fixedToleranceAmount = fixedToleranceAmount;
    }

    public void setApplyRiskMultipliers(boolean applyRiskMultipliers) {
        this.applyRiskMultipliers = applyRiskMultipliers;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    private SimilarGroup findLargestSimilarAmountGroup(List<Transaction> candidates) {
        List<Transaction> sorted = candidates.stream()
            .filter(t -> t.amount() != null)
            .sorted(Comparator.comparing(Transaction::amount))
            .toList();

        SimilarGroup best = new SimilarGroup(BigDecimal.ZERO, BigDecimal.ZERO, List.of());

        for (int i = 0; i < sorted.size(); i++) {
            Transaction anchor = sorted.get(i);
            BigDecimal anchorAmount = anchor.amount();
            BigDecimal tolerance = toleranceFor(anchorAmount);

            List<Transaction> current = new ArrayList<>();
            current.add(anchor);

            for (int j = i + 1; j < sorted.size(); j++) {
                Transaction candidate = sorted.get(j);
                BigDecimal diff = candidate.amount().subtract(anchorAmount).abs();
                if (diff.compareTo(tolerance) <= 0) {
                    current.add(candidate);
                } else {
                    break;
                }
            }

            if (current.size() > best.transactions().size()) {
                best = new SimilarGroup(anchorAmount, tolerance, List.copyOf(current));
            }
        }

        return best;
    }

    private BigDecimal toleranceFor(BigDecimal referenceAmount) {
        BigDecimal percentTolerance = referenceAmount
            .multiply(BigDecimal.valueOf(tolerancePercent))
            .setScale(4, RoundingMode.HALF_UP);

        return percentTolerance.max(fixedToleranceAmount);
    }

    private int adjustedCountThreshold(CustomerContext customer) {
        return switch (customer.riskRating()) {
            case CRITICAL -> Math.max(2, similarityCountThreshold - 3);
            case HIGH -> Math.max(2, similarityCountThreshold - 2);
            case MEDIUM -> Math.max(2, similarityCountThreshold - 1);
            case LOW -> similarityCountThreshold;
        };
    }

    private double calculateDeviationScore(int observedCount, int threshold, BigDecimal referenceAmount) {
        if (threshold <= 0) {
            return 1.0;
        }

        double ratio = BigDecimal.valueOf(observedCount)
            .divide(BigDecimal.valueOf(threshold), 6, RoundingMode.HALF_UP)
            .doubleValue();

        double countFactor = Math.min(0.35, Math.max(0.0, ratio - 1.0) * 0.2);
        double amountFactor = referenceAmount.compareTo(BigDecimal.ZERO) > 0 ? 0.15 : 0.0;

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

    private record SimilarGroup(
        BigDecimal referenceAmount,
        BigDecimal toleranceUsed,
        List<Transaction> transactions
    ) {}
}
