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
 * RULE 090: Upstream Transaction Divergence - Debtor.
 */
public class Rule090UpstreamTransactionDivergenceDebtor implements Rule {

    private static final String RULE_ID = "090";
    private static final String RULE_NAME = "Rule 090: Upstream Transaction Divergence - Debtor";
    private static final String TYPOLOGY = "Channel Diversification / Structuring / Obscuring Origin";
    private static final String REGULATORY_BASIS =
        "Tazama Rule 090 pattern; FATF channel structuring indicators";
    private static final int RULE_PRIORITY = 61;

    private int windowDays = 30;
    private int channelThreshold = 3;
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
            return buildNotTriggered(transaction, customer, start, "Transaction direction is not DEBIT");
        }

        LocalDateTime from = transaction.transactionDate().minusDays(windowDays);
        List<Transaction> history = context.transactionHistoryProvider().findTransactions(
            transaction.accountNumber(), from, transaction.transactionDate().minusNanos(1), Transaction.TransactionDirection.DEBIT
        );

        Set<String> channels = new LinkedHashSet<>();
        for (Transaction tx : history) {
            extractChannel(tx).ifPresent(channels::add);
        }
        extractChannel(transaction).ifPresent(channels::add);

        boolean triggered = channels.size() > channelThreshold;
        double deviationScore = triggered
            ? Math.min(1.0, 0.5 + Math.min(0.5, (double) (channels.size() - channelThreshold) / Math.max(1, channelThreshold)))
            : 0.0;

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("unique_channel_count", channels.size());
        evidence.put("channels", channels);
        evidence.put("channel_threshold", channelThreshold);
        evidence.put("window_days", windowDays);
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
                ? "Investigate unusual channel divergence in debtor outgoing flows"
                : "No immediate action. Channel diversity within expected bounds")
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

    public void setWindowDays(int windowDays) {
        this.windowDays = Math.max(1, windowDays);
    }

    public void setChannelThreshold(int channelThreshold) {
        this.channelThreshold = Math.max(1, channelThreshold);
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    private Optional<String> extractChannel(Transaction tx) {
        Optional<String> byMeta = TransactionMetadataUtil.extractChannel(tx);
        if (byMeta.isPresent()) {
            return byMeta;
        }
        Optional<String> byType = TransactionMetadataUtil.extractType(tx);
        if (byType.isPresent() && byType.get().contains("CRYPTO")) {
            return Optional.of("CRYPTO");
        }
        return Optional.empty();
    }

    private RuleResult buildNotTriggered(Transaction tx, CustomerContext customer, long start, String reason) {
        return new RuleResult(
            RULE_ID, RULE_NAME, false, 0.0, TYPOLOGY, "RC003",
            System.currentTimeMillis() - start, tx.transactionId(), customer.customerId(),
            Map.of("reason_not_triggered", reason), "No action required", REGULATORY_BASIS, java.time.Instant.now()
        );
    }
}
