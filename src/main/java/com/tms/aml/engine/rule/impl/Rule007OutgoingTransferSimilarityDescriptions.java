package com.tms.aml.engine.rule.impl;

import com.tms.aml.domain.CustomerContext;
import com.tms.aml.domain.RuleResult;
import com.tms.aml.domain.Transaction;
import com.tms.aml.engine.rule.Rule;
import com.tms.aml.engine.rule.RuleContext;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RULE 007: Outgoing Transfer Similarity - Descriptions.
 */
public class Rule007OutgoingTransferSimilarityDescriptions implements Rule {

    private static final String RULE_ID = "007";
    private static final String RULE_NAME = "Rule 007: Outgoing Transfer Similarity - Descriptions";
    private static final String TYPOLOGY = "Structuring / Masking / Layering";
    private static final String REGULATORY_BASIS =
        "Tazama Rule 007 - Outgoing transfer similarity - descriptions; FATF Red Flag Indicators; " +
        "EU AML Directive 2015/849; Bank of Tanzania FIU AML Guidelines";
    private static final int RULE_PRIORITY = 78;

    private int observationWindowHours = 24;
    private int minimumOccurrenceCount = 4;
    private double descriptionSimilarityThreshold = 0.90;
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

        List<Transaction> withDescriptions = candidates.stream()
            .filter(t -> t.transactionPurpose() != null && !t.transactionPurpose().isBlank())
            .toList();

        if (withDescriptions.isEmpty()) {
            return buildNotTriggeredResult(transaction, customer, start, "No outgoing descriptions in observation window");
        }

        int effectiveMinOccurrences = applyRiskMultipliers
            ? adjustedOccurrenceThreshold(customer)
            : minimumOccurrenceCount;

        DescriptionGroup bestGroup = findLargestSimilarDescriptionGroup(withDescriptions);

        int occurrenceCount = bestGroup.transactions().size();
        boolean triggered = occurrenceCount >= effectiveMinOccurrences
            && bestGroup.similarityScore() >= descriptionSimilarityThreshold;

        double deviationScore = triggered
            ? calculateDeviationScore(bestGroup.similarityScore(), occurrenceCount, effectiveMinOccurrences)
            : 0.0;

        long evaluationTimeMs = System.currentTimeMillis() - start;

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("time_window_hours", observationWindowHours);
        evidence.put("description_similarity_threshold", descriptionSimilarityThreshold);
        evidence.put("minimum_occurrence_count", effectiveMinOccurrences);
        evidence.put("base_minimum_occurrence_count", minimumOccurrenceCount);
        evidence.put("risk_multipliers_applied", applyRiskMultipliers);
        evidence.put("similarity_score", bestGroup.similarityScore());
        evidence.put("occurrence_count", occurrenceCount);
        evidence.put("similar_descriptions", bestGroup.transactions().stream().map(Transaction::transactionPurpose).toList());
        evidence.put("similar_transaction_ids", bestGroup.transactions().stream().map(Transaction::transactionId).toList());
        evidence.put("canonical_description", bestGroup.canonicalDescription());
        evidence.put("deviation_score", deviationScore);

        String recommendedAction = triggered
            ? "Investigate repeated similar outgoing narrations for masking/structuring behavior and beneficiary linkage"
            : "No immediate action. Continue monitoring outgoing narration patterns";

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

    public void setMinimumOccurrenceCount(int minimumOccurrenceCount) {
        this.minimumOccurrenceCount = Math.max(2, minimumOccurrenceCount);
    }

    public void setDescriptionSimilarityThreshold(double descriptionSimilarityThreshold) {
        this.descriptionSimilarityThreshold = Math.max(0.0, Math.min(1.0, descriptionSimilarityThreshold));
    }

    public void setApplyRiskMultipliers(boolean applyRiskMultipliers) {
        this.applyRiskMultipliers = applyRiskMultipliers;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    private DescriptionGroup findLargestSimilarDescriptionGroup(List<Transaction> candidates) {
        DescriptionGroup best = new DescriptionGroup("", 0.0, List.of());

        for (Transaction anchorTx : candidates) {
            String anchor = normalize(anchorTx.transactionPurpose());
            if (anchor.isBlank()) {
                continue;
            }

            List<Transaction> current = new ArrayList<>();
            double similaritySum = 0.0;

            for (Transaction candidate : candidates) {
                String normalized = normalize(candidate.transactionPurpose());
                if (normalized.isBlank()) {
                    continue;
                }

                double similarity = textSimilarity(anchor, normalized);
                if (similarity >= descriptionSimilarityThreshold) {
                    current.add(candidate);
                    similaritySum += similarity;
                }
            }

            double avgSimilarity = current.isEmpty() ? 0.0 : similaritySum / current.size();
            if (current.size() > best.transactions().size()) {
                best = new DescriptionGroup(anchor, avgSimilarity, List.copyOf(current));
            }
        }

        return best;
    }

    private int adjustedOccurrenceThreshold(CustomerContext customer) {
        return switch (customer.riskRating()) {
            case CRITICAL -> Math.max(2, minimumOccurrenceCount - 2);
            case HIGH -> Math.max(2, minimumOccurrenceCount - 1);
            case MEDIUM -> minimumOccurrenceCount;
            case LOW -> minimumOccurrenceCount;
        };
    }

    private double calculateDeviationScore(double similarityScore, int observedCount, int threshold) {
        if (threshold <= 0) {
            return 1.0;
        }

        double ratio = (double) observedCount / threshold;
        double countFactor = Math.min(0.25, Math.max(0.0, ratio - 1.0) * 0.2);
        double similarityFactor = Math.max(0.0, similarityScore - descriptionSimilarityThreshold);

        return Math.min(1.0, 0.5 + countFactor + Math.min(0.25, similarityFactor));
    }

    private String normalize(String raw) {
        if (raw == null) {
            return "";
        }

        return raw.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9 ]", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private double textSimilarity(String left, String right) {
        if (left.equals(right)) {
            return 1.0;
        }

        Set<String> leftTokens = tokenSet(left);
        Set<String> rightTokens = tokenSet(right);
        double jaccard = jaccardSimilarity(leftTokens, rightTokens);

        double levenshtein = levenshteinSimilarity(left, right);

        return (jaccard * 0.4) + (levenshtein * 0.6);
    }

    private Set<String> tokenSet(String text) {
        if (text.isBlank()) {
            return Set.of();
        }
        return List.of(text.split(" ")).stream()
            .filter(token -> !token.isBlank())
            .collect(Collectors.toSet());
    }

    private double jaccardSimilarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }

        int intersection = 0;
        for (String token : a) {
            if (b.contains(token)) {
                intersection++;
            }
        }

        int union = a.size() + b.size() - intersection;
        if (union == 0) {
            return 0.0;
        }
        return (double) intersection / union;
    }

    private double levenshteinSimilarity(String a, String b) {
        int distance = levenshteinDistance(a, b);
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) {
            return 1.0;
        }
        return 1.0 - ((double) distance / maxLen);
    }

    private int levenshteinDistance(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];

        for (int i = 0; i <= a.length(); i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            dp[0][j] = j;
        }

        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(
                    Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                    dp[i - 1][j - 1] + cost
                );
            }
        }

        return dp[a.length()][b.length()];
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

    private record DescriptionGroup(
        String canonicalDescription,
        double similarityScore,
        List<Transaction> transactions
    ) {}
}
