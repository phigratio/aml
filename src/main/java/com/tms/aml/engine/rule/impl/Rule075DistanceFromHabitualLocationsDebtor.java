package com.tms.aml.engine.rule.impl;

import com.tms.aml.domain.CustomerContext;
import com.tms.aml.domain.RuleResult;
import com.tms.aml.domain.Transaction;
import com.tms.aml.engine.rule.Rule;
import com.tms.aml.engine.rule.RuleContext;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * RULE 075: Distance from Habitual Locations - Debtor.
 */
public class Rule075DistanceFromHabitualLocationsDebtor implements Rule {

    private static final String RULE_ID = "075";
    private static final String RULE_NAME = "Rule 075: Distance from Habitual Locations - Debtor";
    private static final String TYPOLOGY = "Impossible Travel / Location Fraud / Account Takeover / Unusual Geographic Activity";
    private static final String REGULATORY_BASIS =
        "Tazama Rule 075 pattern; FATF location anomaly indicators";
    private static final int RULE_PRIORITY = 66;

    private int historicalWindowDays = 90;
    private int minimumHistoricalLocations = 5;
    private double distanceThresholdKm = 120.0;
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
            return buildNotTriggered(transaction, customer, start, "Transaction direction is not DEBIT");
        }

        Optional<TransactionMetadataUtil.GeoPoint> current = TransactionMetadataUtil.parseGeoPoint(transaction);
        if (current.isEmpty()) {
            return buildNotTriggered(transaction, customer, start, "Current transaction location unavailable");
        }

        LocalDateTime from = transaction.transactionDate().minusDays(historicalWindowDays);
        List<Transaction> history = context.transactionHistoryProvider().findTransactions(
            transaction.accountNumber(), from, transaction.transactionDate().minusNanos(1), Transaction.TransactionDirection.DEBIT
        );

        List<TransactionMetadataUtil.GeoPoint> habitual = new ArrayList<>();
        for (Transaction tx : history) {
            TransactionMetadataUtil.parseGeoPoint(tx).ifPresent(habitual::add);
        }
        extractKnownKycLocations(customer).forEach(habitual::add);

        if (habitual.size() < minimumHistoricalLocations) {
            return buildNotTriggered(transaction, customer, start, "Insufficient habitual location baseline");
        }

        double minDistance = habitual.stream()
            .mapToDouble(loc -> TransactionMetadataUtil.haversineKm(current.get(), loc))
            .min()
            .orElse(Double.POSITIVE_INFINITY);

        double effectiveThreshold = applyRiskMultipliers ? adjustedThreshold(customer) : distanceThresholdKm;
        boolean triggered = minDistance > effectiveThreshold;

        double deviationScore = triggered
            ? Math.min(1.0, 0.5 + Math.min(0.5, (minDistance - effectiveThreshold) / Math.max(1.0, effectiveThreshold)))
            : 0.0;

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("current_location", current.get());
        evidence.put("habitual_location_count", habitual.size());
        evidence.put("minimum_distance_to_habitual_km", minDistance);
        evidence.put("distance_threshold_km", effectiveThreshold);
        evidence.put("base_distance_threshold_km", distanceThresholdKm);
        evidence.put("historical_window_days", historicalWindowDays);
        evidence.put("minimum_historical_locations", minimumHistoricalLocations);
        evidence.put("risk_multipliers_applied", applyRiskMultipliers);
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
                ? "Investigate transaction originating far from habitual debtor locations"
                : "No immediate action. Location aligns with habitual profile")
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

    public void setMinimumHistoricalLocations(int minimumHistoricalLocations) {
        this.minimumHistoricalLocations = Math.max(1, minimumHistoricalLocations);
    }

    public void setDistanceThresholdKm(double distanceThresholdKm) {
        this.distanceThresholdKm = Math.max(1.0, distanceThresholdKm);
    }

    public void setApplyRiskMultipliers(boolean applyRiskMultipliers) {
        this.applyRiskMultipliers = applyRiskMultipliers;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    private double adjustedThreshold(CustomerContext customer) {
        return switch (customer.riskRating()) {
            case CRITICAL -> Math.max(10.0, distanceThresholdKm * 0.60);
            case HIGH -> Math.max(20.0, distanceThresholdKm * 0.75);
            case MEDIUM -> Math.max(30.0, distanceThresholdKm * 0.90);
            case LOW -> distanceThresholdKm;
        };
    }

    private List<TransactionMetadataUtil.GeoPoint> extractKnownKycLocations(CustomerContext customer) {
        List<TransactionMetadataUtil.GeoPoint> locations = new ArrayList<>();
        if (customer.customAttributes() == null) {
            return locations;
        }

        Object home = customer.customAttributes().get("home_geo");
        if (home instanceof String value) {
            TransactionMetadataUtil.parseLatLon(value).ifPresent(locations::add);
        }
        Object work = customer.customAttributes().get("work_geo");
        if (work instanceof String value) {
            TransactionMetadataUtil.parseLatLon(value).ifPresent(locations::add);
        }
        return locations;
    }

    private RuleResult buildNotTriggered(Transaction tx, CustomerContext customer, long start, String reason) {
        return new RuleResult(
            RULE_ID, RULE_NAME, false, 0.0, TYPOLOGY, "RC006",
            System.currentTimeMillis() - start, tx.transactionId(), customer.customerId(),
            Map.of("reason_not_triggered", reason), "No action required", REGULATORY_BASIS, java.time.Instant.now()
        );
    }
}
