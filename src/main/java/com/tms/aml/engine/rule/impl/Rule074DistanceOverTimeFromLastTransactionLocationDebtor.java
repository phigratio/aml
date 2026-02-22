package com.tms.aml.engine.rule.impl;

import com.tms.aml.domain.CustomerContext;
import com.tms.aml.domain.RuleResult;
import com.tms.aml.domain.Transaction;
import com.tms.aml.engine.rule.Rule;
import com.tms.aml.engine.rule.RuleContext;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * RULE 074: Distance over Time from Last Transaction Location - Debtor.
 */
public class Rule074DistanceOverTimeFromLastTransactionLocationDebtor implements Rule {

    private static final String RULE_ID = "074";
    private static final String RULE_NAME = "Rule 074: Distance over Time from Last Transaction Location - Debtor";
    private static final String TYPOLOGY = "Impossible Travel / Location Fraud / Account Takeover";
    private static final String REGULATORY_BASIS =
        "Tazama Rule 074 pattern; FATF geographical inconsistency red flags";
    private static final int RULE_PRIORITY = 67;

    private double maxPlausibleSpeedKmh = 700.0;
    private long minimumTimeDeltaMinutes = 5;
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

        Optional<TransactionMetadataUtil.GeoPoint> currentGeo = TransactionMetadataUtil.parseGeoPoint(transaction);
        if (currentGeo.isEmpty()) {
            return buildNotTriggered(transaction, customer, start, "Current transaction location unavailable");
        }

        Optional<Transaction> latest = context.transactionHistoryProvider().findLatestTransaction(
            transaction.accountNumber(), transaction.transactionDate().minusNanos(1), Transaction.TransactionDirection.DEBIT
        );
        if (latest.isEmpty()) {
            return buildNotTriggered(transaction, customer, start, "No prior debtor transaction found");
        }

        Optional<TransactionMetadataUtil.GeoPoint> lastGeo = TransactionMetadataUtil.parseGeoPoint(latest.get());
        if (lastGeo.isEmpty()) {
            return buildNotTriggered(transaction, customer, start, "Last transaction location unavailable");
        }

        Duration delta = Duration.between(latest.get().transactionDate(), transaction.transactionDate());
        if (delta.isNegative() || delta.toMinutes() < minimumTimeDeltaMinutes) {
            return buildNotTriggered(transaction, customer, start, "Time delta below minimum threshold");
        }

        double distanceKm = TransactionMetadataUtil.haversineKm(lastGeo.get(), currentGeo.get());
        double hours = delta.toSeconds() / 3600.0;
        double speed = distanceKm / Math.max(0.0001, hours);
        boolean triggered = speed > maxPlausibleSpeedKmh;

        double deviationScore = triggered
            ? Math.min(1.0, 0.5 + Math.min(0.5, (speed - maxPlausibleSpeedKmh) / Math.max(1.0, maxPlausibleSpeedKmh)))
            : 0.0;

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("last_location", lastGeo.get());
        evidence.put("current_location", currentGeo.get());
        evidence.put("distance_km", distanceKm);
        evidence.put("time_delta_minutes", delta.toMinutes());
        evidence.put("implied_speed_kmh", speed);
        evidence.put("max_plausible_speed_kmh", maxPlausibleSpeedKmh);
        evidence.put("minimum_time_delta_minutes", minimumTimeDeltaMinutes);
        evidence.put("deviation_score", deviationScore);

        return RuleResult.builder()
            .ruleId(RULE_ID)
            .ruleName(RULE_NAME)
            .triggered(triggered)
            .severityScore(deviationScore)
            .typology(TYPOLOGY)
            .riskCategoryId("RC006")
            .evaluationTimeMs(System.currentTimeMillis() - start)
            .transactionId(transaction.transactionId())
            .customerId(customer.customerId())
            .evidence(evidence)
            .recommendedAction(triggered
                ? "Investigate impossible travel anomaly between consecutive debtor transactions"
                : "No immediate action. Travel speed within plausible range")
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

    public void setMaxPlausibleSpeedKmh(double maxPlausibleSpeedKmh) {
        this.maxPlausibleSpeedKmh = Math.max(1.0, maxPlausibleSpeedKmh);
    }

    public void setMinimumTimeDeltaMinutes(long minimumTimeDeltaMinutes) {
        this.minimumTimeDeltaMinutes = Math.max(1, minimumTimeDeltaMinutes);
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    private RuleResult buildNotTriggered(Transaction tx, CustomerContext customer, long start, String reason) {
        return new RuleResult(
            RULE_ID, RULE_NAME, false, 0.0, TYPOLOGY, "RC006",
            System.currentTimeMillis() - start, tx.transactionId(), customer.customerId(),
            Map.of("reason_not_triggered", reason), "No action required", REGULATORY_BASIS, java.time.Instant.now()
        );
    }
}
