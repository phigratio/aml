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
import java.util.Optional;
import java.util.Set;

/**
 * RULE 078: Transaction Type.
 */
public class Rule078TransactionType implements Rule {

    private static final String RULE_ID = "078";
    private static final String RULE_NAME = "Rule 078: Transaction Type";
    private static final String TYPOLOGY = "High-Risk Transaction Types / Profile Deviation / Layering";
    private static final String REGULATORY_BASIS =
        "Tazama Rule 078 pattern; FATF risk-based monitoring of transaction types";
    private static final int RULE_PRIORITY = 64;

    private Set<String> highRiskTypes = new LinkedHashSet<>(Set.of("INTERNATIONAL_WIRE", "CRYPTO_CONVERSION", "CASH_WITHDRAWAL"));
    private int historicalWindowDays = 90;
    private int minimumProfileSamples = 5;
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

        Optional<String> currentType = TransactionMetadataUtil.extractType(transaction);
        if (currentType.isEmpty()) {
            return buildNotTriggered(transaction, customer, start, "Transaction type not available");
        }

        LocalDateTime from = transaction.transactionDate().minusDays(historicalWindowDays);
        List<Transaction> debits = context.transactionHistoryProvider().findTransactions(
            transaction.accountNumber(), from, transaction.transactionDate().minusNanos(1), Transaction.TransactionDirection.DEBIT
        );
        List<Transaction> credits = context.transactionHistoryProvider().findTransactions(
            transaction.accountNumber(), from, transaction.transactionDate().minusNanos(1), Transaction.TransactionDirection.CREDIT
        );

        Map<String, Integer> counts = new LinkedHashMap<>();
        int samples = 0;
        for (Transaction tx : debits) {
            Optional<String> t = TransactionMetadataUtil.extractType(tx);
            if (t.isPresent()) {
                counts.merge(t.get(), 1, Integer::sum);
                samples++;
            }
        }
        for (Transaction tx : credits) {
            Optional<String> t = TransactionMetadataUtil.extractType(tx);
            if (t.isPresent()) {
                counts.merge(t.get(), 1, Integer::sum);
                samples++;
            }
        }

        boolean isHighRisk = highRiskTypes.contains(currentType.get());
        int historicalCount = counts.getOrDefault(currentType.get(), 0);
        boolean deviatesFromProfile = samples < minimumProfileSamples || historicalCount == 0;
        boolean triggered = isHighRisk && deviatesFromProfile;

        double deviationScore = triggered
            ? Math.min(1.0, 0.6 + Math.min(0.4, (minimumProfileSamples - Math.min(minimumProfileSamples, samples)) * 0.08))
            : 0.0;

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("transaction_type", currentType.get());
        evidence.put("is_high_risk_type", isHighRisk);
        evidence.put("historical_profile_samples", samples);
        evidence.put("minimum_profile_samples", minimumProfileSamples);
        evidence.put("historical_occurrence_of_type", historicalCount);
        evidence.put("high_risk_types", highRiskTypes);
        evidence.put("profile_mismatch", deviatesFromProfile);
        evidence.put("historical_window_days", historicalWindowDays);
        evidence.put("deviation_score", deviationScore);

        return RuleResult.builder()
            .ruleId(RULE_ID)
            .ruleName(RULE_NAME)
            .triggered(triggered)
            .severityScore(deviationScore)
            .typology(TYPOLOGY)
            .riskCategoryId("RC003")
            .evaluationTimeMs(System.currentTimeMillis() - start)
            .transactionId(transaction.transactionId())
            .customerId(customer.customerId())
            .evidence(evidence)
            .recommendedAction(triggered
                ? "Investigate high-risk transaction type deviating from customer profile"
                : "No immediate action. Transaction type aligns with risk policy/profile")
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

    public void setHighRiskTypes(Set<String> highRiskTypes) {
        if (highRiskTypes == null || highRiskTypes.isEmpty()) {
            throw new IllegalArgumentException("highRiskTypes cannot be empty");
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String type : highRiskTypes) {
            if (type != null && !type.isBlank()) {
                normalized.add(type.trim().toUpperCase());
            }
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("highRiskTypes cannot be empty");
        }
        this.highRiskTypes = normalized;
    }

    public void setHistoricalWindowDays(int historicalWindowDays) {
        this.historicalWindowDays = Math.max(1, historicalWindowDays);
    }

    public void setMinimumProfileSamples(int minimumProfileSamples) {
        this.minimumProfileSamples = Math.max(1, minimumProfileSamples);
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    private RuleResult buildNotTriggered(Transaction tx, CustomerContext customer, long start, String reason) {
        return new RuleResult(
            RULE_ID, RULE_NAME, false, 0.0, TYPOLOGY, "RC003",
            System.currentTimeMillis() - start, tx.transactionId(), customer.customerId(),
            Map.of("reason_not_triggered", reason), "No action required", REGULATORY_BASIS, java.time.Instant.now()
        );
    }
}
