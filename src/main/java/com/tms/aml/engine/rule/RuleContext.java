package com.tms.aml.engine.rule;

import com.tms.aml.engine.history.TransactionHistoryProvider;

import java.time.Instant;
import java.util.Map;

/**
 * Shared immutable context passed to each rule evaluation.
 */
public record RuleContext(
    TransactionHistoryProvider transactionHistoryProvider,
    Instant evaluationTimestamp,
    Map<String, Object> attributes
) {
    public RuleContext {
        if (transactionHistoryProvider == null) {
            throw new IllegalArgumentException("transactionHistoryProvider cannot be null");
        }
        if (evaluationTimestamp == null) {
            throw new IllegalArgumentException("evaluationTimestamp cannot be null");
        }
        if (attributes == null) {
            attributes = Map.of();
        } else {
            attributes = Map.copyOf(attributes);
        }
    }
}
