package com.tms.aml.engine.rule.impl;

import com.tms.aml.domain.CustomerContext;
import com.tms.aml.domain.RuleResult;
import com.tms.aml.domain.Transaction;
import com.tms.aml.engine.rule.Rule;
import com.tms.aml.engine.rule.RuleContext;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RULE 008: Outgoing Transfer Similarity - Creditor.
 */
public class Rule008OutgoingTransferSimilarityCreditor implements Rule {

    private static final String RULE_ID = "008";
    private static final String RULE_NAME = "Rule 008: Outgoing Transfer Similarity - Creditor";
    private static final String TYPOLOGY = "Structuring / Layering / Unusual Counterparty Concentration";
    private static final String REGULATORY_BASIS =
        "Tazama Rule 008 - Outgoing transfer similarity - creditor; FATF Red Flag Indicators; " +
        "FinCEN Guidance; Bank of Tanzania FIU AML Guidelines";
    private static final int RULE_PRIORITY = 79;

    private int timeWindowDays = 7;
    private int countThreshold = 5;
    private BigDecimal minimumTotalAmount = new BigDecimal("0.00");
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

        String creditorKey = creditorKey(transaction);
        if (creditorKey == null) {
            return buildNotTriggeredResult(transaction, customer, start, "Creditor details are missing");
        }

        LocalDateTime windowStart = transaction.transactionDate().minusDays(timeWindowDays);
        List<Transaction> outgoing = context.transactionHistoryProvider().findTransactions(
            transaction.accountNumber(),
            windowStart,
            transaction.transactionDate(),
            Transaction.TransactionDirection.DEBIT
        );

        List<Transaction> all = new ArrayList<>(outgoing);
        all.add(transaction);

        List<Transaction> toSameCreditor = all.stream()
            .filter(tx -> creditorKey.equals(creditorKey(tx)))
            .sorted(Comparator.comparing(Transaction::transactionDate))
            .toList();

        int effectiveCountThreshold = applyRiskMultipliers
            ? adjustedCountThreshold(customer)
            : countThreshold;

        BigDecimal effectiveMinimumAmount = applyRiskMultipliers
            ? adjustedMinimumAmount(customer)
            : minimumTotalAmount;

        int txCount = toSameCreditor.size();
        BigDecimal totalAmount = toSameCreditor.stream()
            .map(Transaction::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        boolean triggered = txCount >= effectiveCountThreshold
            && totalAmount.compareTo(effectiveMinimumAmount) >= 0;

        double deviationScore = triggered
            ? calculateDeviationScore(txCount, effectiveCountThreshold, totalAmount, effectiveMinimumAmount)
            : 0.0;

        long evaluationTimeMs = System.currentTimeMillis() - start;

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("creditor_id", creditorKey);
        evidence.put("transaction_count_to_creditor", txCount);
        evidence.put("threshold_count", effectiveCountThreshold);
        evidence.put("base_threshold_count", countThreshold);
        evidence.put("total_amount_to_creditor", totalAmount);
        evidence.put("minimum_total_amount", effectiveMinimumAmount);
        evidence.put("base_minimum_total_amount", minimumTotalAmount);
        evidence.put("time_window_days", timeWindowDays);
        evidence.put("window_start", windowStart);
        evidence.put("window_end", transaction.transactionDate());
        evidence.put("risk_multipliers_applied", applyRiskMultipliers);
        evidence.put("matching_transaction_ids", toSameCreditor.stream().map(Transaction::transactionId).toList());
        evidence.put("deviation_score", deviationScore);

        String recommendedAction = triggered
            ? "Investigate repeated outgoing transfers to concentrated beneficiary; validate relationship and economic purpose"
            : "No immediate action. Continue monitoring beneficiary concentration";

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

    public void setTimeWindowDays(int timeWindowDays) {
        this.timeWindowDays = Math.max(1, timeWindowDays);
    }

    public void setCountThreshold(int countThreshold) {
        this.countThreshold = Math.max(2, countThreshold);
    }

    public void setMinimumTotalAmount(BigDecimal minimumTotalAmount) {
        if (minimumTotalAmount == null || minimumTotalAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("minimumTotalAmount must be >= 0");
        }
        this.minimumTotalAmount = minimumTotalAmount;
    }

    public void setApplyRiskMultipliers(boolean applyRiskMultipliers) {
        this.applyRiskMultipliers = applyRiskMultipliers;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    private String creditorKey(Transaction transaction) {
        if (transaction.counterpartyAccount() != null && !transaction.counterpartyAccount().isBlank()) {
            return "ACCOUNT:" + transaction.counterpartyAccount().trim();
        }
        if (transaction.counterpartyName() != null && !transaction.counterpartyName().isBlank()) {
            return "NAME:" + transaction.counterpartyName().trim().toUpperCase();
        }
        return null;
    }

    private int adjustedCountThreshold(CustomerContext customer) {
        return switch (customer.riskRating()) {
            case CRITICAL -> Math.max(2, countThreshold - 2);
            case HIGH -> Math.max(2, countThreshold - 1);
            case MEDIUM -> countThreshold;
            case LOW -> countThreshold;
        };
    }

    private BigDecimal adjustedMinimumAmount(CustomerContext customer) {
        return switch (customer.riskRating()) {
            case CRITICAL -> minimumTotalAmount.multiply(new BigDecimal("0.70"));
            case HIGH -> minimumTotalAmount.multiply(new BigDecimal("0.85"));
            case MEDIUM -> minimumTotalAmount;
            case LOW -> minimumTotalAmount;
        };
    }

    private double calculateDeviationScore(
        int count,
        int countThreshold,
        BigDecimal totalAmount,
        BigDecimal minimumAmount
    ) {
        double countRatio = countThreshold <= 0 ? 1.0 : (double) count / countThreshold;
        double amountRatio = minimumAmount.compareTo(BigDecimal.ZERO) == 0
            ? 1.0
            : totalAmount.divide(minimumAmount, 6, java.math.RoundingMode.HALF_UP).doubleValue();

        double countFactor = Math.min(0.3, Math.max(0.0, countRatio - 1.0) * 0.2);
        double amountFactor = Math.min(0.2, Math.max(0.0, amountRatio - 1.0) * 0.1);

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
}
