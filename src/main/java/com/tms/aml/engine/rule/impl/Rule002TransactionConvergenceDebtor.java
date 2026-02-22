package com.tms.aml.engine.rule.impl;

import com.tms.aml.domain.CustomerContext;
import com.tms.aml.domain.RuleResult;
import com.tms.aml.domain.Transaction;
import com.tms.aml.engine.rule.Rule;
import com.tms.aml.engine.rule.RuleContext;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RULE 002: Transaction Convergence - Debtor.
 *
 * Detects many unique senders funneling funds into the same beneficiary account
 * within a configurable time window.
 */
public class Rule002TransactionConvergenceDebtor implements Rule {

    private static final String RULE_ID = "002";
    private static final String RULE_NAME = "Rule 002: Transaction Convergence - Debtor";
    private static final String TYPOLOGY = "Layering / Mule Account / Aggregation";
    private static final String REGULATORY_BASIS =
        "Tazama Rule 002 - Transaction convergence - debtor; " +
        "FATF Red Flag Indicators; " +
        "Bank of Tanzania FIU AML Guidelines";
    private static final int RULE_PRIORITY = 85;

    private int windowHours = 24;
    private int uniqueSenderThreshold = 5;
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

        if (!transaction.isCredit()) {
            return buildNotTriggeredResult(transaction, customer, start, "Transaction direction is not CREDIT");
        }

        LocalDateTime windowStart = transaction.transactionDate().minusHours(windowHours);
        List<Transaction> historicalInbound = context.transactionHistoryProvider().findInboundTransactions(
            transaction.accountNumber(),
            windowStart,
            transaction.transactionDate()
        );

        Set<String> uniqueSenders = new LinkedHashSet<>();
        for (Transaction tx : historicalInbound) {
            senderKey(tx).ifPresent(uniqueSenders::add);
        }
        senderKey(transaction).ifPresent(uniqueSenders::add);

        int uniqueSenderCount = uniqueSenders.size();
        double deviationScore = calculateDeviationScore(uniqueSenderCount, uniqueSenderThreshold);
        boolean triggered = uniqueSenderCount > uniqueSenderThreshold;
        long evaluationTimeMs = System.currentTimeMillis() - start;

        Map<String, Object> evidence = Map.of(
            "account_number", transaction.accountNumber(),
            "unique_sender_count", uniqueSenderCount,
            "unique_senders", List.copyOf(uniqueSenders),
            "time_window_hours", windowHours,
            "window_start", windowStart,
            "window_end", transaction.transactionDate(),
            "sender_threshold", uniqueSenderThreshold,
            "deviation_score", deviationScore,
            "inbound_transaction_count", historicalInbound.size() + 1
        );

        String recommendedAction = triggered
            ? "Investigate potential layering/mule aggregation; review sender relationships and source of funds"
            : "No immediate action. Continue monitoring for convergence escalation";

        return RuleResult.builder()
            .ruleId(RULE_ID)
            .ruleName(RULE_NAME)
            .triggered(triggered)
            .severityScore(triggered ? deviationScore : 0.0)
            .typology(TYPOLOGY)
            .riskCategoryId("RC002")
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

    public void setUniqueSenderThreshold(int uniqueSenderThreshold) {
        this.uniqueSenderThreshold = Math.max(1, uniqueSenderThreshold);
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    private java.util.Optional<String> senderKey(Transaction transaction) {
        String account = transaction.counterpartyAccount();
        if (account != null && !account.isBlank()) {
            return java.util.Optional.of("ACCOUNT:" + account.trim());
        }

        String name = transaction.counterpartyName();
        if (name != null && !name.isBlank()) {
            return java.util.Optional.of("NAME:" + name.trim().toUpperCase());
        }

        return java.util.Optional.empty();
    }

    private double calculateDeviationScore(int uniqueSenderCount, int threshold) {
        if (threshold <= 0) {
            return 1.0;
        }

        double ratio = BigDecimal.valueOf(uniqueSenderCount)
            .divide(BigDecimal.valueOf(threshold), 6, java.math.RoundingMode.HALF_UP)
            .doubleValue();

        // Normalized score: threshold = 0.5, then asymptotically moves to 1.0
        double score = Math.min(1.0, 0.5 + Math.max(0.0, ratio - 1.0) * 0.5);
        return Math.max(0.0, score);
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
            "RC002",
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
