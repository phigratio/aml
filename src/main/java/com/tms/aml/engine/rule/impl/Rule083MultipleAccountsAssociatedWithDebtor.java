package com.tms.aml.engine.rule.impl;

import com.tms.aml.domain.CustomerContext;
import com.tms.aml.domain.RuleResult;
import com.tms.aml.domain.Transaction;
import com.tms.aml.engine.rule.Rule;
import com.tms.aml.engine.rule.RuleContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RULE 083: Multiple Accounts Associated with a Debtor.
 */
public class Rule083MultipleAccountsAssociatedWithDebtor implements Rule {

    private static final String RULE_ID = "083";
    private static final String RULE_NAME = "Rule 083: Multiple Accounts Associated with a Debtor";
    private static final String TYPOLOGY = "Multi-Account Layering / Structuring / Obscuring Ownership";
    private static final String REGULATORY_BASIS =
        "Tazama Rule 083 pattern; FATF CDD linked-account monitoring";
    private static final int RULE_PRIORITY = 63;

    private int multiAccountThreshold = 3;
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

        List<String> linkedAccounts = extractLinkedAccounts(customer, "linked_debtor_accounts");
        boolean triggered = linkedAccounts.size() >= multiAccountThreshold;

        double deviationScore = triggered
            ? Math.min(1.0, 0.5 + Math.min(0.5, (double) (linkedAccounts.size() - multiAccountThreshold + 1) / Math.max(1, multiAccountThreshold)))
            : 0.0;

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("linked_account_count", linkedAccounts.size());
        evidence.put("linked_accounts", linkedAccounts);
        evidence.put("multi_account_threshold", multiAccountThreshold);
        evidence.put("matched_identifier_source", "customer.customAttributes.linked_debtor_accounts");
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
                ? "Investigate potential debtor multi-account layering pattern"
                : "No immediate action. Linked account count below threshold")
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

    public void setMultiAccountThreshold(int multiAccountThreshold) {
        this.multiAccountThreshold = Math.max(1, multiAccountThreshold);
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    private List<String> extractLinkedAccounts(CustomerContext customer, String key) {
        List<String> result = new ArrayList<>();
        if (customer.customAttributes() == null) {
            return result;
        }
        Object raw = customer.customAttributes().get(key);
        if (raw instanceof String value) {
            for (String token : value.split(",")) {
                String trimmed = token.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
        } else if (raw instanceof Collection<?> values) {
            for (Object obj : values) {
                if (obj != null && !obj.toString().isBlank()) {
                    result.add(obj.toString().trim());
                }
            }
        }
        return result;
    }

    private RuleResult buildNotTriggered(Transaction tx, CustomerContext customer, long start, String reason) {
        return new RuleResult(
            RULE_ID, RULE_NAME, false, 0.0, TYPOLOGY, "RC003",
            System.currentTimeMillis() - start, tx.transactionId(), customer.customerId(),
            Map.of("reason_not_triggered", reason), "No action required", REGULATORY_BASIS, java.time.Instant.now()
        );
    }
}
