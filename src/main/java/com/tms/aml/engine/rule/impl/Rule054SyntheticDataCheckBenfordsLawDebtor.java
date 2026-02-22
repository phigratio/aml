package com.tms.aml.engine.rule.impl;

import com.tms.aml.domain.CustomerContext;
import com.tms.aml.domain.RuleResult;
import com.tms.aml.domain.Transaction;
import com.tms.aml.engine.rule.Rule;
import com.tms.aml.engine.rule.RuleContext;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RULE 054: Synthetic Data Check - Benford's Law - Debtor.
 */
public class Rule054SyntheticDataCheckBenfordsLawDebtor implements Rule {

    private static final String RULE_ID = "054";
    private static final String RULE_NAME = "Rule 054: Synthetic Data Check - Benford's Law - Debtor";
    private static final String TYPOLOGY = "Data Fabrication / Synthetic Transactions / Fraud";
    private static final String REGULATORY_BASIS =
        "Tazama Rule 054 pattern; Benford forensic analytics for anomalous transaction distributions";
    private static final int RULE_PRIORITY = 69;

    private int historicalWindowDays = 90;
    private int minimumSamples = 100;
    private double madThreshold = 0.06;
    private boolean enabled = true;

    private static final double[] BENFORD = {
        0.0,
        0.3010, 0.1761, 0.1249, 0.0969, 0.0792,
        0.0669, 0.0580, 0.0512, 0.0458
    };

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

        LocalDateTime from = transaction.transactionDate().minusDays(historicalWindowDays);
        List<Transaction> history = context.transactionHistoryProvider().findTransactions(
            transaction.accountNumber(), from, transaction.transactionDate(), Transaction.TransactionDirection.DEBIT
        );

        int[] counts = new int[10];
        int sampleSize = 0;

        for (Transaction tx : history) {
            int d = firstDigit(tx.amount());
            if (d > 0) {
                counts[d]++;
                sampleSize++;
            }
        }

        if (sampleSize < minimumSamples) {
            return buildNotTriggeredResult(transaction, customer, start, "Insufficient sample size for Benford evaluation");
        }

        double mad = 0.0;
        Map<String, Double> observed = new LinkedHashMap<>();
        Map<String, Double> expected = new LinkedHashMap<>();

        for (int d = 1; d <= 9; d++) {
            double obs = (double) counts[d] / sampleSize;
            observed.put(Integer.toString(d), obs);
            expected.put(Integer.toString(d), BENFORD[d]);
            mad += Math.abs(obs - BENFORD[d]);
        }
        mad /= 9.0;

        boolean triggered = mad > madThreshold;
        double deviationScore = triggered
            ? Math.min(1.0, 0.5 + Math.min(0.5, (mad - madThreshold) / Math.max(0.01, madThreshold)))
            : 0.0;

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("sample_size", sampleSize);
        evidence.put("minimum_samples", minimumSamples);
        evidence.put("mad", mad);
        evidence.put("mad_threshold", madThreshold);
        evidence.put("historical_window_days", historicalWindowDays);
        evidence.put("observed_distribution", observed);
        evidence.put("expected_distribution", expected);
        evidence.put("deviation_score", deviationScore);

        return RuleResult.builder()
            .ruleId(RULE_ID)
            .ruleName(RULE_NAME)
            .triggered(triggered)
            .severityScore(deviationScore)
            .typology(TYPOLOGY)
            .riskCategoryId("RC005")
            .evaluationTimeMs(System.currentTimeMillis() - start)
            .transactionId(transaction.transactionId())
            .customerId(customer.customerId())
            .evidence(evidence)
            .recommendedAction(triggered
                ? "Investigate potential synthetic or fabricated outgoing transaction amounts"
                : "No immediate action. Benford deviation within threshold")
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

    public void setHistoricalWindowDays(int historicalWindowDays) {
        this.historicalWindowDays = Math.max(1, historicalWindowDays);
    }

    public void setMinimumSamples(int minimumSamples) {
        this.minimumSamples = Math.max(10, minimumSamples);
    }

    public void setMadThreshold(double madThreshold) {
        this.madThreshold = Math.max(0.0001, madThreshold);
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    private int firstDigit(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        String normalized = amount.stripTrailingZeros().abs().toPlainString().replace(".", "");
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c >= '1' && c <= '9') {
                return c - '0';
            }
        }
        return 0;
    }

    private RuleResult buildNotTriggeredResult(Transaction transaction, CustomerContext customer, long start, String reason) {
        return new RuleResult(
            RULE_ID,
            RULE_NAME,
            false,
            0.0,
            TYPOLOGY,
            "RC005",
            System.currentTimeMillis() - start,
            transaction.transactionId(),
            customer.customerId(),
            Map.of("reason_not_triggered", reason),
            "No action required",
            REGULATORY_BASIS,
            java.time.Instant.now()
        );
    }
}
