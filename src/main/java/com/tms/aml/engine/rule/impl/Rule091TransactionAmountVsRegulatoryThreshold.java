package com.tms.aml.engine.rule.impl;

import com.tms.aml.domain.CustomerContext;
import com.tms.aml.domain.RuleResult;
import com.tms.aml.domain.Transaction;
import com.tms.aml.engine.rule.Rule;
import com.tms.aml.engine.rule.RuleContext;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * RULE 091: Transaction Amount vs Regulatory Threshold.
 */
public class Rule091TransactionAmountVsRegulatoryThreshold implements Rule {

    private static final String RULE_ID = "091";
    private static final String RULE_NAME = "Rule 091: Transaction Amount vs Regulatory Threshold";
    private static final String TYPOLOGY = "Threshold Reporting / CTR/STR Triggers / Large Transactions";
    private static final String REGULATORY_BASIS =
        "Tazama Rule 091 pattern; FATF Recommendation 10; jurisdictional reporting thresholds";
    private static final int RULE_PRIORITY = 60;

    private BigDecimal defaultThreshold = new BigDecimal("10000.00");
    private Map<String, BigDecimal> jurisdictionThresholds = new LinkedHashMap<>();
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

        String jurisdiction = customer.jurisdiction() == null
            ? "DEFAULT"
            : customer.jurisdiction().trim().toUpperCase(Locale.ROOT);

        BigDecimal threshold = jurisdictionThresholds.getOrDefault(jurisdiction, defaultThreshold);
        boolean triggered = transaction.amount().compareTo(threshold) >= 0;

        double ratio = threshold.compareTo(BigDecimal.ZERO) <= 0
            ? 1.0
            : transaction.amount().divide(threshold, 6, java.math.RoundingMode.HALF_UP).doubleValue();

        double deviationScore = triggered
            ? Math.min(1.0, 0.5 + Math.min(0.5, Math.max(0.0, ratio - 1.0) * 0.5))
            : 0.0;

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("transaction_amount", transaction.amount());
        evidence.put("regulatory_threshold", threshold);
        evidence.put("jurisdiction", jurisdiction);
        evidence.put("threshold_ratio", ratio);
        evidence.put("potential_report_flag", triggered ? "CTR_OR_EQUIVALENT" : "NONE");
        evidence.put("deviation_score", deviationScore);

        return RuleResult.builder()
            .ruleId(RULE_ID)
            .ruleName(RULE_NAME)
            .triggered(triggered)
            .severityScore(deviationScore)
            .typology(TYPOLOGY)
            .riskCategoryId("RC007")
            .evaluationTimeMs(System.currentTimeMillis() - start)
            .transactionId(transaction.transactionId())
            .customerId(customer.customerId())
            .evidence(evidence)
            .recommendedAction(triggered
                ? "Review for regulatory threshold reporting and enhanced due diligence"
                : "No immediate action. Transaction amount below reporting threshold")
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

    public void setDefaultThreshold(BigDecimal defaultThreshold) {
        if (defaultThreshold == null || defaultThreshold.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("defaultThreshold must be > 0");
        }
        this.defaultThreshold = defaultThreshold;
    }

    public void setJurisdictionThresholds(Map<String, BigDecimal> jurisdictionThresholds) {
        Map<String, BigDecimal> normalized = new LinkedHashMap<>();
        if (jurisdictionThresholds != null) {
            for (Map.Entry<String, BigDecimal> entry : jurisdictionThresholds.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null && entry.getValue().compareTo(BigDecimal.ZERO) > 0) {
                    normalized.put(entry.getKey().trim().toUpperCase(Locale.ROOT), entry.getValue());
                }
            }
        }
        this.jurisdictionThresholds = normalized;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
