package com.tms.aml.engine.rule.impl;

import com.tms.aml.domain.CustomerContext;
import com.tms.aml.domain.RuleResult;
import com.tms.aml.domain.Transaction;
import com.tms.aml.engine.rule.Rule;
import com.tms.aml.engine.rule.RuleContext;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * RULE 030: Transfer to Unfamiliar Creditor Account - Debtor.
 */
public class Rule030TransferToUnfamiliarCreditorAccountDebtor implements Rule {

    private static final String RULE_ID = "030";
    private static final String RULE_NAME = "Rule 030: Transfer to Unfamiliar Creditor Account - Debtor";
    private static final String TYPOLOGY = "Money Mule / New Payee / Fraud";
    private static final String REGULATORY_BASIS =
        "Tazama Rule 030 pattern; FATF unexpected counterparty indicators";
    private static final int RULE_PRIORITY = 73;

    private int lookbackDays = 180;
    private BigDecimal minimumAmount = new BigDecimal("500.00");
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

        Optional<String> currentCreditor = creditorKey(transaction);
        if (currentCreditor.isEmpty()) {
            return buildNotTriggeredResult(transaction, customer, start, "No creditor identifier found in transaction");
        }

        BigDecimal effectiveMinimumAmount = applyRiskMultipliers ? adjustedMinimumAmount(customer) : minimumAmount;
        if (transaction.amount().compareTo(effectiveMinimumAmount) < 0) {
            return buildNotTriggeredResult(transaction, customer, start, "Transaction amount below minimum unfamiliar-payee threshold");
        }

        LocalDateTime from = transaction.transactionDate().minusDays(lookbackDays);
        List<Transaction> historicalOutgoing = context.transactionHistoryProvider().findTransactions(
            transaction.accountNumber(), from, transaction.transactionDate().minusNanos(1), Transaction.TransactionDirection.DEBIT
        );

        Set<String> knownCreditors = new LinkedHashSet<>();
        for (Transaction tx : historicalOutgoing) {
            creditorKey(tx).ifPresent(knownCreditors::add);
        }

        boolean unfamiliar = !knownCreditors.contains(currentCreditor.get());
        double deviationScore = unfamiliar
            ? Math.min(1.0, 0.5 + Math.min(0.3, transaction.amount().divide(effectiveMinimumAmount, 6, java.math.RoundingMode.HALF_UP).doubleValue() * 0.05))
            : 0.0;

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("current_creditor", currentCreditor.get());
        evidence.put("is_unfamiliar_creditor", unfamiliar);
        evidence.put("known_creditor_count", knownCreditors.size());
        evidence.put("lookback_days", lookbackDays);
        evidence.put("transaction_amount", transaction.amount());
        evidence.put("minimum_amount", effectiveMinimumAmount);
        evidence.put("base_minimum_amount", minimumAmount);
        evidence.put("risk_multipliers_applied", applyRiskMultipliers);
        evidence.put("deviation_score", deviationScore);

        return RuleResult.builder()
            .ruleId(RULE_ID)
            .ruleName(RULE_NAME)
            .triggered(unfamiliar)
            .severityScore(deviationScore)
            .typology(TYPOLOGY)
            .riskCategoryId("RC003")
            .evaluationTimeMs(System.currentTimeMillis() - start)
            .transactionId(transaction.transactionId())
            .customerId(customer.customerId())
            .evidence(evidence)
            .recommendedAction(unfamiliar
                ? "Investigate high-value transfer to previously unseen creditor"
                : "No immediate action. Creditor is present in historical counterparty profile")
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

    public void setLookbackDays(int lookbackDays) {
        this.lookbackDays = Math.max(1, lookbackDays);
    }

    public void setMinimumAmount(BigDecimal minimumAmount) {
        if (minimumAmount == null || minimumAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("minimumAmount must be >= 0");
        }
        this.minimumAmount = minimumAmount;
    }

    public void setApplyRiskMultipliers(boolean applyRiskMultipliers) {
        this.applyRiskMultipliers = applyRiskMultipliers;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    private BigDecimal adjustedMinimumAmount(CustomerContext customer) {
        return switch (customer.riskRating()) {
            case CRITICAL -> minimumAmount.multiply(new BigDecimal("0.50"));
            case HIGH -> minimumAmount.multiply(new BigDecimal("0.70"));
            case MEDIUM -> minimumAmount;
            case LOW -> minimumAmount;
        };
    }

    private Optional<String> creditorKey(Transaction transaction) {
        if (transaction.counterpartyAccount() != null && !transaction.counterpartyAccount().isBlank()) {
            return Optional.of("ACCOUNT:" + transaction.counterpartyAccount().trim());
        }
        if (transaction.counterpartyName() != null && !transaction.counterpartyName().isBlank()) {
            return Optional.of("NAME:" + transaction.counterpartyName().trim().toUpperCase());
        }
        return Optional.empty();
    }

    private RuleResult buildNotTriggeredResult(Transaction transaction, CustomerContext customer, long start, String reason) {
        return new RuleResult(
            RULE_ID,
            RULE_NAME,
            false,
            0.0,
            TYPOLOGY,
            "RC003",
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
