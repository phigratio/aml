package com.tms.aml.config;

import com.tms.aml.engine.AMLRuleEngine;
import com.tms.aml.engine.history.InMemoryTransactionHistoryProvider;
import com.tms.aml.engine.history.PostgresTransactionHistoryProvider;
import com.tms.aml.engine.history.TransactionHistoryProvider;
import com.tms.aml.engine.rule.impl.Rule001DerivedAccountAgeCreditor;
import com.tms.aml.engine.rule.impl.Rule002TransactionConvergenceDebtor;
import com.tms.aml.engine.rule.impl.Rule003AccountDormancyCreditor;
import com.tms.aml.engine.rule.impl.Rule004AccountDormancyDebtor;
import com.tms.aml.engine.rule.impl.Rule006OutgoingTransferSimilarityAmounts;
import com.tms.aml.engine.rule.impl.Rule007OutgoingTransferSimilarityDescriptions;
import com.tms.aml.engine.rule.impl.Rule008OutgoingTransferSimilarityCreditor;
import com.tms.aml.engine.rule.impl.Rule010IncreasedAccountActivityVolumeDebtor;
import com.tms.aml.engine.rule.impl.Rule011IncreasedAccountActivityVolumeCreditor;
import com.tms.aml.engine.rule.impl.Rule016TransactionConvergenceCreditor;
import com.tms.aml.engine.rule.impl.Rule017TransactionDivergenceDebtor;
import com.tms.aml.engine.rule.impl.Rule018ExceptionallyLargeOutgoingTransferDebtor;
import com.tms.aml.engine.rule.impl.Rule020LargeTransactionAmountVsHistoryCreditor;
import com.tms.aml.engine.rule.impl.Rule021LargeNumberSimilarTransactionAmountsCreditor;
import com.tms.aml.engine.rule.impl.Rule024NonCommissionedTransactionMirroringCreditor;
import com.tms.aml.engine.rule.impl.Rule025NonCommissionedTransactionMirroringDebtor;
import com.tms.aml.engine.rule.impl.Rule026CommissionedTransactionMirroringCreditor;
import com.tms.aml.engine.rule.impl.Rule027CommissionedTransactionMirroringDebtor;
import com.tms.aml.engine.rule.impl.Rule028AgeClassificationDebtor;
import com.tms.aml.engine.rule.impl.Rule030TransferToUnfamiliarCreditorAccountDebtor;
import com.tms.aml.engine.rule.impl.Rule044SuccessfulTransactionsFromDebtorIncludingNewOne;
import com.tms.aml.engine.rule.impl.Rule045SuccessfulTransactionsToCreditorIncludingNewOne;
import com.tms.aml.engine.rule.impl.Rule048LargeTransactionAmountVsHistoryDebtor;
import com.tms.aml.engine.rule.impl.Rule054SyntheticDataCheckBenfordsLawDebtor;
import com.tms.aml.engine.rule.impl.Rule063SyntheticDataCheckBenfordsLawCreditor;
import com.tms.aml.engine.rule.impl.Rule074DistanceOverTimeFromLastTransactionLocationDebtor;
import com.tms.aml.engine.rule.impl.Rule075DistanceFromHabitualLocationsDebtor;
import com.tms.aml.engine.rule.impl.Rule076TimeSinceLastTransactionDebtor;
import com.tms.aml.engine.rule.impl.Rule078TransactionType;
import com.tms.aml.engine.rule.impl.Rule083MultipleAccountsAssociatedWithDebtor;
import com.tms.aml.engine.rule.impl.Rule084MultipleAccountsAssociatedWithCreditor;
import com.tms.aml.engine.rule.impl.Rule090UpstreamTransactionDivergenceDebtor;
import com.tms.aml.engine.rule.impl.Rule091TransactionAmountVsRegulatoryThreshold;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * AML Rule Engine Configuration
 * 
 * Spring Boot Configuration class that sets up the AML rule engine
 * with proper dependency injection, rule registration, and configuration
 * properties management.
 * 
 * Configuration Properties:
 * aml.engine.rule001.age-threshold-days (default: 30)
 * aml.engine.rule001.amount-threshold (default: 5000)
 * aml.engine.rule001.enabled (default: true)
 * aml.engine.rule001.apply-risk-adjustments (default: true)
 * 
 * @author AML Engineering Team
 */
@Configuration
public class AMLRuleEngineConfiguration {
    
    /**
     * Initialize and configure the AML Rule Engine
     * Registers all available rules and applies configuration
     * 
     * @param rule001 Autowired Rule 001 implementation
     * @param properties AML configuration properties
     * @return Configured RuleEngine instance
     */
    @Bean
    public AMLRuleEngine amlRuleEngine(
        Rule001DerivedAccountAgeCreditor rule001,
        Rule002TransactionConvergenceDebtor rule002,
        Rule003AccountDormancyCreditor rule003,
        Rule004AccountDormancyDebtor rule004,
        Rule006OutgoingTransferSimilarityAmounts rule006,
        Rule007OutgoingTransferSimilarityDescriptions rule007,
        Rule008OutgoingTransferSimilarityCreditor rule008,
        Rule010IncreasedAccountActivityVolumeDebtor rule010,
        Rule011IncreasedAccountActivityVolumeCreditor rule011,
        Rule016TransactionConvergenceCreditor rule016,
        Rule017TransactionDivergenceDebtor rule017,
        Rule018ExceptionallyLargeOutgoingTransferDebtor rule018,
        Rule020LargeTransactionAmountVsHistoryCreditor rule020,
        Rule021LargeNumberSimilarTransactionAmountsCreditor rule021,
        Rule024NonCommissionedTransactionMirroringCreditor rule024,
        Rule025NonCommissionedTransactionMirroringDebtor rule025,
        Rule026CommissionedTransactionMirroringCreditor rule026,
        Rule027CommissionedTransactionMirroringDebtor rule027,
        Rule028AgeClassificationDebtor rule028,
        Rule030TransferToUnfamiliarCreditorAccountDebtor rule030,
        Rule044SuccessfulTransactionsFromDebtorIncludingNewOne rule044,
        Rule045SuccessfulTransactionsToCreditorIncludingNewOne rule045,
        Rule048LargeTransactionAmountVsHistoryDebtor rule048,
        Rule054SyntheticDataCheckBenfordsLawDebtor rule054,
        Rule063SyntheticDataCheckBenfordsLawCreditor rule063,
        Rule074DistanceOverTimeFromLastTransactionLocationDebtor rule074,
        Rule075DistanceFromHabitualLocationsDebtor rule075,
        Rule076TimeSinceLastTransactionDebtor rule076,
        Rule078TransactionType rule078,
        Rule083MultipleAccountsAssociatedWithDebtor rule083,
        Rule084MultipleAccountsAssociatedWithCreditor rule084,
        Rule090UpstreamTransactionDivergenceDebtor rule090,
        Rule091TransactionAmountVsRegulatoryThreshold rule091,
        TransactionHistoryProvider transactionHistoryProvider,
        AMLRuleEngineProperties properties
    ) {
        AMLRuleEngine engine = new AMLRuleEngine(transactionHistoryProvider);
        
        // Apply Rule 001 configuration
        rule001.setAgeThresholdDays(properties.getRule001().getAgeThresholdDays());
        rule001.setAmountThreshold(new BigDecimal(properties.getRule001().getAmountThreshold()));
        rule001.setEnabled(properties.getRule001().isEnabled());
        rule001.setApplyRiskBasedAdjustments(properties.getRule001().isApplyRiskAdjustments());

        rule002.setWindowHours(properties.getRule002().getWindowHours());
        rule002.setUniqueSenderThreshold(properties.getRule002().getUniqueSenderThreshold());
        rule002.setEnabled(properties.getRule002().isEnabled());

        rule003.setDormancyThresholdDays(properties.getRule003().getDormancyThresholdDays());
        rule003.setAmountThreshold(new BigDecimal(properties.getRule003().getAmountThreshold()));
        rule003.setEnabled(properties.getRule003().isEnabled());

        rule004.setDormancyThresholdDays(properties.getRule004().getDormancyThresholdDays());
        rule004.setAlertThreshold(new BigDecimal(properties.getRule004().getAlertThreshold()));
        rule004.setApplyRiskMultipliers(properties.getRule004().isApplyRiskMultipliers());
        rule004.setEnabled(properties.getRule004().isEnabled());

        rule006.setObservationWindowHours(properties.getRule006().getObservationWindowHours());
        rule006.setSimilarityCountThreshold(properties.getRule006().getSimilarityCountThreshold());
        rule006.setTolerancePercent(properties.getRule006().getTolerancePercent());
        rule006.setFixedToleranceAmount(new BigDecimal(properties.getRule006().getFixedToleranceAmount()));
        rule006.setApplyRiskMultipliers(properties.getRule006().isApplyRiskMultipliers());
        rule006.setEnabled(properties.getRule006().isEnabled());

        rule007.setObservationWindowHours(properties.getRule007().getObservationWindowHours());
        rule007.setMinimumOccurrenceCount(properties.getRule007().getMinimumOccurrenceCount());
        rule007.setDescriptionSimilarityThreshold(properties.getRule007().getDescriptionSimilarityThreshold());
        rule007.setApplyRiskMultipliers(properties.getRule007().isApplyRiskMultipliers());
        rule007.setEnabled(properties.getRule007().isEnabled());

        rule008.setTimeWindowDays(properties.getRule008().getTimeWindowDays());
        rule008.setCountThreshold(properties.getRule008().getCountThreshold());
        rule008.setMinimumTotalAmount(new BigDecimal(properties.getRule008().getMinimumTotalAmount()));
        rule008.setApplyRiskMultipliers(properties.getRule008().isApplyRiskMultipliers());
        rule008.setEnabled(properties.getRule008().isEnabled());

        rule010.setCurrentPeriodDays(properties.getRule010().getCurrentPeriodDays());
        rule010.setHistoricalPeriodDays(properties.getRule010().getHistoricalPeriodDays());
        rule010.setSpikeFactor(properties.getRule010().getSpikeFactor());
        rule010.setMinimumCurrentPeriodTransactionCount(properties.getRule010().getMinimumCurrentPeriodTransactionCount());
        rule010.setApplyRiskMultipliers(properties.getRule010().isApplyRiskMultipliers());
        rule010.setEnabled(properties.getRule010().isEnabled());

        rule011.setCurrentPeriodDays(properties.getRule011().getCurrentPeriodDays());
        rule011.setHistoricalPeriodDays(properties.getRule011().getHistoricalPeriodDays());
        rule011.setSpikeFactor(properties.getRule011().getSpikeFactor());
        rule011.setMinimumCurrentPeriodTransactionCount(properties.getRule011().getMinimumCurrentPeriodTransactionCount());
        rule011.setApplyRiskMultipliers(properties.getRule011().isApplyRiskMultipliers());
        rule011.setEnabled(properties.getRule011().isEnabled());

        rule016.setWindowHours(properties.getRule016().getWindowHours());
        rule016.setUniqueDebtorThreshold(properties.getRule016().getUniqueDebtorThreshold());
        rule016.setMinimumWindowVolume(new BigDecimal(properties.getRule016().getMinimumWindowVolume()));
        rule016.setApplyRiskMultipliers(properties.getRule016().isApplyRiskMultipliers());
        rule016.setEnabled(properties.getRule016().isEnabled());

        rule017.setTimeWindowHours(properties.getRule017().getTimeWindowHours());
        rule017.setUniqueCreditorThreshold(properties.getRule017().getUniqueCreditorThreshold());
        rule017.setMinimumOutgoingTransactionCount(properties.getRule017().getMinimumOutgoingTransactionCount());
        rule017.setApplyRiskMultipliers(properties.getRule017().isApplyRiskMultipliers());
        rule017.setEnabled(properties.getRule017().isEnabled());

        rule018.setHistoricalWindowDays(properties.getRule018().getHistoricalWindowDays());
        rule018.setThresholdMultiplier(properties.getRule018().getThresholdMultiplier());
        rule018.setMinimumHistoricalTransactions(properties.getRule018().getMinimumHistoricalTransactions());
        rule018.setAbsoluteMinimumAmountFloor(new BigDecimal(properties.getRule018().getAbsoluteMinimumAmountFloor()));
        rule018.setApplyRiskMultipliers(properties.getRule018().isApplyRiskMultipliers());
        rule018.setEnabled(properties.getRule018().isEnabled());

        rule020.setHistoricalWindowDays(properties.getRule020().getHistoricalWindowDays());
        rule020.setThresholdMultiplier(properties.getRule020().getThresholdMultiplier());
        rule020.setMinimumHistoricalTransactions(properties.getRule020().getMinimumHistoricalTransactions());
        rule020.setApplyRiskMultipliers(properties.getRule020().isApplyRiskMultipliers());
        rule020.setEnabled(properties.getRule020().isEnabled());

        rule021.setWindowHours(properties.getRule021().getWindowHours());
        rule021.setCountThreshold(properties.getRule021().getCountThreshold());
        rule021.setTolerancePercent(properties.getRule021().getTolerancePercent());
        rule021.setMinimumTotalIncomingVolume(new BigDecimal(properties.getRule021().getMinimumTotalIncomingVolume()));
        rule021.setApplyRiskMultipliers(properties.getRule021().isApplyRiskMultipliers());
        rule021.setEnabled(properties.getRule021().isEnabled());

        rule024.setWindowHours(properties.getRule024().getWindowHours());
        rule024.setPairThreshold(properties.getRule024().getPairThreshold());
        rule024.setAmountTolerancePercent(properties.getRule024().getAmountTolerancePercent());
        rule024.setExcludeSameCounterparty(properties.getRule024().isExcludeSameCounterparty());
        rule024.setApplyRiskMultipliers(properties.getRule024().isApplyRiskMultipliers());
        rule024.setEnabled(properties.getRule024().isEnabled());

        rule025.setWindowHours(properties.getRule025().getWindowHours());
        rule025.setMatchThreshold(properties.getRule025().getMatchThreshold());
        rule025.setAmountTolerancePercent(properties.getRule025().getAmountTolerancePercent());
        rule025.setApplyRiskMultipliers(properties.getRule025().isApplyRiskMultipliers());
        rule025.setEnabled(properties.getRule025().isEnabled());

        rule026.setWindowHours(properties.getRule026().getWindowHours());
        rule026.setMatchThreshold(properties.getRule026().getMatchThreshold());
        rule026.setAmountTolerancePercent(properties.getRule026().getAmountTolerancePercent());
        rule026.setExpectedCommissionMinPercent(properties.getRule026().getExpectedCommissionMinPercent());
        rule026.setExpectedCommissionMaxPercent(properties.getRule026().getExpectedCommissionMaxPercent());
        rule026.setMaxCommissionVariance(properties.getRule026().getMaxCommissionVariance());
        rule026.setApplyRiskMultipliers(properties.getRule026().isApplyRiskMultipliers());
        rule026.setEnabled(properties.getRule026().isEnabled());

        rule027.setWindowHours(properties.getRule027().getWindowHours());
        rule027.setMatchThreshold(properties.getRule027().getMatchThreshold());
        rule027.setAmountTolerancePercent(properties.getRule027().getAmountTolerancePercent());
        rule027.setExpectedCommissionPercent(properties.getRule027().getExpectedCommissionPercent());
        rule027.setCommissionTolerancePercent(properties.getRule027().getCommissionTolerancePercent());
        rule027.setApplyRiskMultipliers(properties.getRule027().isApplyRiskMultipliers());
        rule027.setEnabled(properties.getRule027().isEnabled());

        rule028.setAgeThresholdDays(properties.getRule028().getAgeThresholdDays());
        rule028.setVelocityWindowHours(properties.getRule028().getVelocityWindowHours());
        rule028.setMinimumTransactionCount(properties.getRule028().getMinimumTransactionCount());
        rule028.setVolumeSpikeFactor(properties.getRule028().getVolumeSpikeFactor());
        rule028.setApplyRiskMultipliers(properties.getRule028().isApplyRiskMultipliers());
        rule028.setEnabled(properties.getRule028().isEnabled());

        rule030.setLookbackDays(properties.getRule030().getLookbackDays());
        rule030.setMinimumAmount(new BigDecimal(properties.getRule030().getMinimumAmount()));
        rule030.setApplyRiskMultipliers(properties.getRule030().isApplyRiskMultipliers());
        rule030.setEnabled(properties.getRule030().isEnabled());

        rule044.setCurrentPeriodDays(properties.getRule044().getCurrentPeriodDays());
        rule044.setHistoricalPeriodDays(properties.getRule044().getHistoricalPeriodDays());
        rule044.setCountSpikeFactor(properties.getRule044().getCountSpikeFactor());
        rule044.setApplyRiskMultipliers(properties.getRule044().isApplyRiskMultipliers());
        rule044.setEnabled(properties.getRule044().isEnabled());

        rule045.setCurrentPeriodDays(properties.getRule045().getCurrentPeriodDays());
        rule045.setHistoricalPeriodDays(properties.getRule045().getHistoricalPeriodDays());
        rule045.setCountSpikeFactor(properties.getRule045().getCountSpikeFactor());
        rule045.setApplyRiskMultipliers(properties.getRule045().isApplyRiskMultipliers());
        rule045.setEnabled(properties.getRule045().isEnabled());

        rule048.setHistoricalWindowDays(properties.getRule048().getHistoricalWindowDays());
        rule048.setThresholdMultiplier(properties.getRule048().getThresholdMultiplier());
        rule048.setMinimumHistoricalTransactions(properties.getRule048().getMinimumHistoricalTransactions());
        rule048.setApplyRiskMultipliers(properties.getRule048().isApplyRiskMultipliers());
        rule048.setEnabled(properties.getRule048().isEnabled());

        rule054.setHistoricalWindowDays(properties.getRule054().getHistoricalWindowDays());
        rule054.setMinimumSamples(properties.getRule054().getMinimumSamples());
        rule054.setMadThreshold(properties.getRule054().getMadThreshold());
        rule054.setEnabled(properties.getRule054().isEnabled());

        rule063.setHistoricalWindowDays(properties.getRule063().getHistoricalWindowDays());
        rule063.setMinimumSamples(properties.getRule063().getMinimumSamples());
        rule063.setMadThreshold(properties.getRule063().getMadThreshold());
        rule063.setEnabled(properties.getRule063().isEnabled());

        rule074.setMaxPlausibleSpeedKmh(properties.getRule074().getMaxPlausibleSpeedKmh());
        rule074.setMinimumTimeDeltaMinutes(properties.getRule074().getMinimumTimeDeltaMinutes());
        rule074.setEnabled(properties.getRule074().isEnabled());

        rule075.setHistoricalWindowDays(properties.getRule075().getHistoricalWindowDays());
        rule075.setMinimumHistoricalLocations(properties.getRule075().getMinimumHistoricalLocations());
        rule075.setDistanceThresholdKm(properties.getRule075().getDistanceThresholdKm());
        rule075.setApplyRiskMultipliers(properties.getRule075().isApplyRiskMultipliers());
        rule075.setEnabled(properties.getRule075().isEnabled());

        rule076.setInactivityThresholdDays(properties.getRule076().getInactivityThresholdDays());
        rule076.setBurstThresholdMinutes(properties.getRule076().getBurstThresholdMinutes());
        rule076.setEnabled(properties.getRule076().isEnabled());

        rule078.setHighRiskTypes(new java.util.LinkedHashSet<>(properties.getRule078().getHighRiskTypes()));
        rule078.setHistoricalWindowDays(properties.getRule078().getHistoricalWindowDays());
        rule078.setMinimumProfileSamples(properties.getRule078().getMinimumProfileSamples());
        rule078.setEnabled(properties.getRule078().isEnabled());

        rule083.setMultiAccountThreshold(properties.getRule083().getMultiAccountThreshold());
        rule083.setEnabled(properties.getRule083().isEnabled());

        rule084.setMultiAccountThreshold(properties.getRule084().getMultiAccountThreshold());
        rule084.setEnabled(properties.getRule084().isEnabled());

        rule090.setWindowDays(properties.getRule090().getWindowDays());
        rule090.setChannelThreshold(properties.getRule090().getChannelThreshold());
        rule090.setEnabled(properties.getRule090().isEnabled());

        rule091.setDefaultThreshold(new BigDecimal(properties.getRule091().getDefaultThreshold()));
        rule091.setJurisdictionThresholds(parseThresholdMap(properties.getRule091().getJurisdictionThresholds()));
        rule091.setEnabled(properties.getRule091().isEnabled());
        
        // Register Rule 001
        engine.registerRule(rule001);
        engine.registerRule(rule002);
        engine.registerRule(rule003);
        engine.registerRule(rule004);
        engine.registerRule(rule006);
        engine.registerRule(rule007);
        engine.registerRule(rule008);
        engine.registerRule(rule010);
        engine.registerRule(rule011);
        engine.registerRule(rule016);
        engine.registerRule(rule017);
        engine.registerRule(rule018);
        engine.registerRule(rule020);
        engine.registerRule(rule021);
        engine.registerRule(rule024);
        engine.registerRule(rule025);
        engine.registerRule(rule026);
        engine.registerRule(rule027);
        engine.registerRule(rule028);
        engine.registerRule(rule030);
        engine.registerRule(rule044);
        engine.registerRule(rule045);
        engine.registerRule(rule048);
        engine.registerRule(rule054);
        engine.registerRule(rule063);
        engine.registerRule(rule074);
        engine.registerRule(rule075);
        engine.registerRule(rule076);
        engine.registerRule(rule078);
        engine.registerRule(rule083);
        engine.registerRule(rule084);
        engine.registerRule(rule090);
        engine.registerRule(rule091);
        
        return engine;
    }
    
    /**
     * Rule 001 Bean - Ensures single instance managed by Spring
     * Enables autowiring and configuration injection
     * 
     * @return Rule 001 implementation
     */
    @Bean
    public Rule001DerivedAccountAgeCreditor rule001DerivedAccountAgeCreditor() {
        return new Rule001DerivedAccountAgeCreditor();
    }

    @Bean
    public Rule002TransactionConvergenceDebtor rule002TransactionConvergenceDebtor() {
        return new Rule002TransactionConvergenceDebtor();
    }

    @Bean
    public Rule003AccountDormancyCreditor rule003AccountDormancyCreditor() {
        return new Rule003AccountDormancyCreditor();
    }

    @Bean
    public Rule004AccountDormancyDebtor rule004AccountDormancyDebtor() {
        return new Rule004AccountDormancyDebtor();
    }

    @Bean
    public Rule006OutgoingTransferSimilarityAmounts rule006OutgoingTransferSimilarityAmounts() {
        return new Rule006OutgoingTransferSimilarityAmounts();
    }

    @Bean
    public Rule007OutgoingTransferSimilarityDescriptions rule007OutgoingTransferSimilarityDescriptions() {
        return new Rule007OutgoingTransferSimilarityDescriptions();
    }

    @Bean
    public Rule008OutgoingTransferSimilarityCreditor rule008OutgoingTransferSimilarityCreditor() {
        return new Rule008OutgoingTransferSimilarityCreditor();
    }

    @Bean
    public Rule010IncreasedAccountActivityVolumeDebtor rule010IncreasedAccountActivityVolumeDebtor() {
        return new Rule010IncreasedAccountActivityVolumeDebtor();
    }

    @Bean
    public Rule011IncreasedAccountActivityVolumeCreditor rule011IncreasedAccountActivityVolumeCreditor() {
        return new Rule011IncreasedAccountActivityVolumeCreditor();
    }

    @Bean
    public Rule016TransactionConvergenceCreditor rule016TransactionConvergenceCreditor() {
        return new Rule016TransactionConvergenceCreditor();
    }

    @Bean
    public Rule017TransactionDivergenceDebtor rule017TransactionDivergenceDebtor() {
        return new Rule017TransactionDivergenceDebtor();
    }

    @Bean
    public Rule018ExceptionallyLargeOutgoingTransferDebtor rule018ExceptionallyLargeOutgoingTransferDebtor() {
        return new Rule018ExceptionallyLargeOutgoingTransferDebtor();
    }

    @Bean
    public Rule020LargeTransactionAmountVsHistoryCreditor rule020LargeTransactionAmountVsHistoryCreditor() {
        return new Rule020LargeTransactionAmountVsHistoryCreditor();
    }

    @Bean
    public Rule021LargeNumberSimilarTransactionAmountsCreditor rule021LargeNumberSimilarTransactionAmountsCreditor() {
        return new Rule021LargeNumberSimilarTransactionAmountsCreditor();
    }

    @Bean
    public Rule024NonCommissionedTransactionMirroringCreditor rule024NonCommissionedTransactionMirroringCreditor() {
        return new Rule024NonCommissionedTransactionMirroringCreditor();
    }

    @Bean
    public Rule025NonCommissionedTransactionMirroringDebtor rule025NonCommissionedTransactionMirroringDebtor() {
        return new Rule025NonCommissionedTransactionMirroringDebtor();
    }

    @Bean
    public Rule026CommissionedTransactionMirroringCreditor rule026CommissionedTransactionMirroringCreditor() {
        return new Rule026CommissionedTransactionMirroringCreditor();
    }

    @Bean
    public Rule027CommissionedTransactionMirroringDebtor rule027CommissionedTransactionMirroringDebtor() {
        return new Rule027CommissionedTransactionMirroringDebtor();
    }

    @Bean
    public Rule028AgeClassificationDebtor rule028AgeClassificationDebtor() {
        return new Rule028AgeClassificationDebtor();
    }

    @Bean
    public Rule030TransferToUnfamiliarCreditorAccountDebtor rule030TransferToUnfamiliarCreditorAccountDebtor() {
        return new Rule030TransferToUnfamiliarCreditorAccountDebtor();
    }

    @Bean
    public Rule044SuccessfulTransactionsFromDebtorIncludingNewOne rule044SuccessfulTransactionsFromDebtorIncludingNewOne() {
        return new Rule044SuccessfulTransactionsFromDebtorIncludingNewOne();
    }

    @Bean
    public Rule045SuccessfulTransactionsToCreditorIncludingNewOne rule045SuccessfulTransactionsToCreditorIncludingNewOne() {
        return new Rule045SuccessfulTransactionsToCreditorIncludingNewOne();
    }

    @Bean
    public Rule048LargeTransactionAmountVsHistoryDebtor rule048LargeTransactionAmountVsHistoryDebtor() {
        return new Rule048LargeTransactionAmountVsHistoryDebtor();
    }

    @Bean
    public Rule054SyntheticDataCheckBenfordsLawDebtor rule054SyntheticDataCheckBenfordsLawDebtor() {
        return new Rule054SyntheticDataCheckBenfordsLawDebtor();
    }

    @Bean
    public Rule063SyntheticDataCheckBenfordsLawCreditor rule063SyntheticDataCheckBenfordsLawCreditor() {
        return new Rule063SyntheticDataCheckBenfordsLawCreditor();
    }

    @Bean
    public Rule074DistanceOverTimeFromLastTransactionLocationDebtor rule074DistanceOverTimeFromLastTransactionLocationDebtor() {
        return new Rule074DistanceOverTimeFromLastTransactionLocationDebtor();
    }

    @Bean
    public Rule075DistanceFromHabitualLocationsDebtor rule075DistanceFromHabitualLocationsDebtor() {
        return new Rule075DistanceFromHabitualLocationsDebtor();
    }

    @Bean
    public Rule076TimeSinceLastTransactionDebtor rule076TimeSinceLastTransactionDebtor() {
        return new Rule076TimeSinceLastTransactionDebtor();
    }

    @Bean
    public Rule078TransactionType rule078TransactionType() {
        return new Rule078TransactionType();
    }

    @Bean
    public Rule083MultipleAccountsAssociatedWithDebtor rule083MultipleAccountsAssociatedWithDebtor() {
        return new Rule083MultipleAccountsAssociatedWithDebtor();
    }

    @Bean
    public Rule084MultipleAccountsAssociatedWithCreditor rule084MultipleAccountsAssociatedWithCreditor() {
        return new Rule084MultipleAccountsAssociatedWithCreditor();
    }

    @Bean
    public Rule090UpstreamTransactionDivergenceDebtor rule090UpstreamTransactionDivergenceDebtor() {
        return new Rule090UpstreamTransactionDivergenceDebtor();
    }

    @Bean
    public Rule091TransactionAmountVsRegulatoryThreshold rule091TransactionAmountVsRegulatoryThreshold() {
        return new Rule091TransactionAmountVsRegulatoryThreshold();
    }

    @Bean
    public TransactionHistoryProvider transactionHistoryProvider(
        AMLRuleEngineProperties properties,
        ObjectProvider<JdbcTemplate> jdbcTemplateProvider
    ) {
        String provider = properties.getHistory().getProvider() == null
            ? "in-memory"
            : properties.getHistory().getProvider().toLowerCase(Locale.ROOT);
        if ("postgres".equals(provider)) {
            JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
            if (jdbcTemplate == null) {
                throw new IllegalStateException(
                    "History provider is set to postgres but no JdbcTemplate is configured"
                );
            }
            return new PostgresTransactionHistoryProvider(
                jdbcTemplate,
                properties.getHistory().getTableName()
            );
        }

        return new InMemoryTransactionHistoryProvider(
            Duration.ofHours(properties.getHistory().getRetentionHours()),
            properties.getHistory().getMaxTransactionsPerAccount()
        );
    }

    private Map<String, BigDecimal> parseThresholdMap(Map<String, String> source) {
        Map<String, BigDecimal> result = new java.util.LinkedHashMap<>();
        if (source == null) {
            return result;
        }
        for (Map.Entry<String, String> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            try {
                result.put(entry.getKey().trim().toUpperCase(Locale.ROOT), new BigDecimal(entry.getValue().trim()));
            } catch (NumberFormatException ignored) {
                // Ignore malformed threshold values to keep boot resilient.
            }
        }
        return result;
    }
}

/**
 * AML Engine Configuration Properties
 * 
 * Maps application.yml configuration to strongly-typed properties
 * Example YAML:
 * 
 * aml:
 *   engine:
 *     rule001:
 *       age-threshold-days: 30
 *       amount-threshold: 5000.00
 *       enabled: true
 *       apply-risk-adjustments: true
 */
@Component
@ConfigurationProperties(prefix = "aml.engine")
class AMLRuleEngineProperties {
    
    private Rule001Properties rule001 = new Rule001Properties();
    private Rule002Properties rule002 = new Rule002Properties();
    private Rule003Properties rule003 = new Rule003Properties();
    private Rule004Properties rule004 = new Rule004Properties();
    private Rule006Properties rule006 = new Rule006Properties();
    private Rule007Properties rule007 = new Rule007Properties();
    private Rule008Properties rule008 = new Rule008Properties();
    private Rule010Properties rule010 = new Rule010Properties();
    private Rule011Properties rule011 = new Rule011Properties();
    private Rule016Properties rule016 = new Rule016Properties();
    private Rule017Properties rule017 = new Rule017Properties();
    private Rule018Properties rule018 = new Rule018Properties();
    private Rule020Properties rule020 = new Rule020Properties();
    private Rule021Properties rule021 = new Rule021Properties();
    private Rule024Properties rule024 = new Rule024Properties();
    private Rule025Properties rule025 = new Rule025Properties();
    private Rule026Properties rule026 = new Rule026Properties();
    private Rule027Properties rule027 = new Rule027Properties();
    private Rule028Properties rule028 = new Rule028Properties();
    private Rule030Properties rule030 = new Rule030Properties();
    private Rule044Properties rule044 = new Rule044Properties();
    private Rule045Properties rule045 = new Rule045Properties();
    private Rule048Properties rule048 = new Rule048Properties();
    private Rule054Properties rule054 = new Rule054Properties();
    private Rule063Properties rule063 = new Rule063Properties();
    private Rule074Properties rule074 = new Rule074Properties();
    private Rule075Properties rule075 = new Rule075Properties();
    private Rule076Properties rule076 = new Rule076Properties();
    private Rule078Properties rule078 = new Rule078Properties();
    private Rule083Properties rule083 = new Rule083Properties();
    private Rule084Properties rule084 = new Rule084Properties();
    private Rule090Properties rule090 = new Rule090Properties();
    private Rule091Properties rule091 = new Rule091Properties();
    private HistoryProperties history = new HistoryProperties();
    
    public Rule001Properties getRule001() {
        return rule001;
    }
    
    public void setRule001(Rule001Properties rule001) {
        this.rule001 = rule001;
    }

    public Rule002Properties getRule002() {
        return rule002;
    }

    public void setRule002(Rule002Properties rule002) {
        this.rule002 = rule002;
    }

    public Rule003Properties getRule003() {
        return rule003;
    }

    public void setRule003(Rule003Properties rule003) {
        this.rule003 = rule003;
    }

    public Rule004Properties getRule004() {
        return rule004;
    }

    public void setRule004(Rule004Properties rule004) {
        this.rule004 = rule004;
    }

    public Rule006Properties getRule006() {
        return rule006;
    }

    public void setRule006(Rule006Properties rule006) {
        this.rule006 = rule006;
    }

    public Rule007Properties getRule007() {
        return rule007;
    }

    public void setRule007(Rule007Properties rule007) {
        this.rule007 = rule007;
    }

    public Rule008Properties getRule008() {
        return rule008;
    }

    public void setRule008(Rule008Properties rule008) {
        this.rule008 = rule008;
    }

    public Rule010Properties getRule010() {
        return rule010;
    }

    public void setRule010(Rule010Properties rule010) {
        this.rule010 = rule010;
    }

    public Rule011Properties getRule011() {
        return rule011;
    }

    public void setRule011(Rule011Properties rule011) {
        this.rule011 = rule011;
    }

    public Rule016Properties getRule016() {
        return rule016;
    }

    public void setRule016(Rule016Properties rule016) {
        this.rule016 = rule016;
    }

    public Rule017Properties getRule017() {
        return rule017;
    }

    public void setRule017(Rule017Properties rule017) {
        this.rule017 = rule017;
    }

    public Rule018Properties getRule018() {
        return rule018;
    }

    public void setRule018(Rule018Properties rule018) {
        this.rule018 = rule018;
    }

    public Rule020Properties getRule020() {
        return rule020;
    }

    public void setRule020(Rule020Properties rule020) {
        this.rule020 = rule020;
    }

    public Rule021Properties getRule021() {
        return rule021;
    }

    public void setRule021(Rule021Properties rule021) {
        this.rule021 = rule021;
    }

    public Rule024Properties getRule024() {
        return rule024;
    }

    public void setRule024(Rule024Properties rule024) {
        this.rule024 = rule024;
    }

    public Rule025Properties getRule025() {
        return rule025;
    }

    public void setRule025(Rule025Properties rule025) {
        this.rule025 = rule025;
    }

    public Rule026Properties getRule026() {
        return rule026;
    }

    public void setRule026(Rule026Properties rule026) {
        this.rule026 = rule026;
    }

    public Rule027Properties getRule027() {
        return rule027;
    }

    public void setRule027(Rule027Properties rule027) {
        this.rule027 = rule027;
    }

    public Rule028Properties getRule028() {
        return rule028;
    }

    public void setRule028(Rule028Properties rule028) {
        this.rule028 = rule028;
    }

    public Rule030Properties getRule030() {
        return rule030;
    }

    public void setRule030(Rule030Properties rule030) {
        this.rule030 = rule030;
    }

    public Rule044Properties getRule044() {
        return rule044;
    }

    public void setRule044(Rule044Properties rule044) {
        this.rule044 = rule044;
    }

    public Rule045Properties getRule045() {
        return rule045;
    }

    public void setRule045(Rule045Properties rule045) {
        this.rule045 = rule045;
    }

    public Rule048Properties getRule048() {
        return rule048;
    }

    public void setRule048(Rule048Properties rule048) {
        this.rule048 = rule048;
    }

    public Rule054Properties getRule054() {
        return rule054;
    }

    public void setRule054(Rule054Properties rule054) {
        this.rule054 = rule054;
    }

    public Rule063Properties getRule063() {
        return rule063;
    }

    public void setRule063(Rule063Properties rule063) {
        this.rule063 = rule063;
    }

    public Rule074Properties getRule074() {
        return rule074;
    }

    public void setRule074(Rule074Properties rule074) {
        this.rule074 = rule074;
    }

    public Rule075Properties getRule075() {
        return rule075;
    }

    public void setRule075(Rule075Properties rule075) {
        this.rule075 = rule075;
    }

    public Rule076Properties getRule076() {
        return rule076;
    }

    public void setRule076(Rule076Properties rule076) {
        this.rule076 = rule076;
    }

    public Rule078Properties getRule078() {
        return rule078;
    }

    public void setRule078(Rule078Properties rule078) {
        this.rule078 = rule078;
    }

    public Rule083Properties getRule083() {
        return rule083;
    }

    public void setRule083(Rule083Properties rule083) {
        this.rule083 = rule083;
    }

    public Rule084Properties getRule084() {
        return rule084;
    }

    public void setRule084(Rule084Properties rule084) {
        this.rule084 = rule084;
    }

    public Rule090Properties getRule090() {
        return rule090;
    }

    public void setRule090(Rule090Properties rule090) {
        this.rule090 = rule090;
    }

    public Rule091Properties getRule091() {
        return rule091;
    }

    public void setRule091(Rule091Properties rule091) {
        this.rule091 = rule091;
    }

    public HistoryProperties getHistory() {
        return history;
    }

    public void setHistory(HistoryProperties history) {
        this.history = history;
    }
    
    /**
     * Rule 001 Specific Configuration
     */
    static class Rule001Properties {
        
        private int ageThresholdDays = 30;
        private String amountThreshold = "5000.00";
        private boolean enabled = true;
        private boolean applyRiskAdjustments = true;
        
        // Getters and Setters
        
        public int getAgeThresholdDays() {
            return ageThresholdDays;
        }
        
        public void setAgeThresholdDays(int ageThresholdDays) {
            this.ageThresholdDays = ageThresholdDays;
        }
        
        public String getAmountThreshold() {
            return amountThreshold;
        }
        
        public void setAmountThreshold(String amountThreshold) {
            this.amountThreshold = amountThreshold;
        }
        
        public boolean isEnabled() {
            return enabled;
        }
        
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        
        public boolean isApplyRiskAdjustments() {
            return applyRiskAdjustments;
        }
        
        public void setApplyRiskAdjustments(boolean applyRiskAdjustments) {
            this.applyRiskAdjustments = applyRiskAdjustments;
        }
    }

    static class Rule002Properties {
        private int windowHours = 24;
        private int uniqueSenderThreshold = 5;
        private boolean enabled = true;

        public int getWindowHours() {
            return windowHours;
        }

        public void setWindowHours(int windowHours) {
            this.windowHours = windowHours;
        }

        public int getUniqueSenderThreshold() {
            return uniqueSenderThreshold;
        }

        public void setUniqueSenderThreshold(int uniqueSenderThreshold) {
            this.uniqueSenderThreshold = uniqueSenderThreshold;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    static class Rule003Properties {
        private int dormancyThresholdDays = 90;
        private String amountThreshold = "5000.00";
        private boolean enabled = true;

        public int getDormancyThresholdDays() {
            return dormancyThresholdDays;
        }

        public void setDormancyThresholdDays(int dormancyThresholdDays) {
            this.dormancyThresholdDays = dormancyThresholdDays;
        }

        public String getAmountThreshold() {
            return amountThreshold;
        }

        public void setAmountThreshold(String amountThreshold) {
            this.amountThreshold = amountThreshold;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    static class Rule004Properties {
        private int dormancyThresholdDays = 90;
        private String alertThreshold = "5000.00";
        private boolean applyRiskMultipliers = true;
        private boolean enabled = true;

        public int getDormancyThresholdDays() {
            return dormancyThresholdDays;
        }

        public void setDormancyThresholdDays(int dormancyThresholdDays) {
            this.dormancyThresholdDays = dormancyThresholdDays;
        }

        public String getAlertThreshold() {
            return alertThreshold;
        }

        public void setAlertThreshold(String alertThreshold) {
            this.alertThreshold = alertThreshold;
        }

        public boolean isApplyRiskMultipliers() {
            return applyRiskMultipliers;
        }

        public void setApplyRiskMultipliers(boolean applyRiskMultipliers) {
            this.applyRiskMultipliers = applyRiskMultipliers;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    static class Rule006Properties {
        private int observationWindowHours = 24;
        private int similarityCountThreshold = 5;
        private double tolerancePercent = 0.01;
        private String fixedToleranceAmount = "1000.00";
        private boolean applyRiskMultipliers = true;
        private boolean enabled = true;

        public int getObservationWindowHours() {
            return observationWindowHours;
        }

        public void setObservationWindowHours(int observationWindowHours) {
            this.observationWindowHours = observationWindowHours;
        }

        public int getSimilarityCountThreshold() {
            return similarityCountThreshold;
        }

        public void setSimilarityCountThreshold(int similarityCountThreshold) {
            this.similarityCountThreshold = similarityCountThreshold;
        }

        public double getTolerancePercent() {
            return tolerancePercent;
        }

        public void setTolerancePercent(double tolerancePercent) {
            this.tolerancePercent = tolerancePercent;
        }

        public String getFixedToleranceAmount() {
            return fixedToleranceAmount;
        }

        public void setFixedToleranceAmount(String fixedToleranceAmount) {
            this.fixedToleranceAmount = fixedToleranceAmount;
        }

        public boolean isApplyRiskMultipliers() {
            return applyRiskMultipliers;
        }

        public void setApplyRiskMultipliers(boolean applyRiskMultipliers) {
            this.applyRiskMultipliers = applyRiskMultipliers;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    static class Rule007Properties {
        private int observationWindowHours = 24;
        private int minimumOccurrenceCount = 4;
        private double descriptionSimilarityThreshold = 0.90;
        private boolean applyRiskMultipliers = true;
        private boolean enabled = true;

        public int getObservationWindowHours() {
            return observationWindowHours;
        }

        public void setObservationWindowHours(int observationWindowHours) {
            this.observationWindowHours = observationWindowHours;
        }

        public int getMinimumOccurrenceCount() {
            return minimumOccurrenceCount;
        }

        public void setMinimumOccurrenceCount(int minimumOccurrenceCount) {
            this.minimumOccurrenceCount = minimumOccurrenceCount;
        }

        public double getDescriptionSimilarityThreshold() {
            return descriptionSimilarityThreshold;
        }

        public void setDescriptionSimilarityThreshold(double descriptionSimilarityThreshold) {
            this.descriptionSimilarityThreshold = descriptionSimilarityThreshold;
        }

        public boolean isApplyRiskMultipliers() {
            return applyRiskMultipliers;
        }

        public void setApplyRiskMultipliers(boolean applyRiskMultipliers) {
            this.applyRiskMultipliers = applyRiskMultipliers;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    static class Rule008Properties {
        private int timeWindowDays = 7;
        private int countThreshold = 5;
        private String minimumTotalAmount = "0.00";
        private boolean applyRiskMultipliers = true;
        private boolean enabled = true;

        public int getTimeWindowDays() {
            return timeWindowDays;
        }

        public void setTimeWindowDays(int timeWindowDays) {
            this.timeWindowDays = timeWindowDays;
        }

        public int getCountThreshold() {
            return countThreshold;
        }

        public void setCountThreshold(int countThreshold) {
            this.countThreshold = countThreshold;
        }

        public String getMinimumTotalAmount() {
            return minimumTotalAmount;
        }

        public void setMinimumTotalAmount(String minimumTotalAmount) {
            this.minimumTotalAmount = minimumTotalAmount;
        }

        public boolean isApplyRiskMultipliers() {
            return applyRiskMultipliers;
        }

        public void setApplyRiskMultipliers(boolean applyRiskMultipliers) {
            this.applyRiskMultipliers = applyRiskMultipliers;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    static class Rule010Properties {
        private int currentPeriodDays = 7;
        private int historicalPeriodDays = 30;
        private double spikeFactor = 3.0;
        private int minimumCurrentPeriodTransactionCount = 3;
        private boolean applyRiskMultipliers = true;
        private boolean enabled = true;

        public int getCurrentPeriodDays() {
            return currentPeriodDays;
        }

        public void setCurrentPeriodDays(int currentPeriodDays) {
            this.currentPeriodDays = currentPeriodDays;
        }

        public int getHistoricalPeriodDays() {
            return historicalPeriodDays;
        }

        public void setHistoricalPeriodDays(int historicalPeriodDays) {
            this.historicalPeriodDays = historicalPeriodDays;
        }

        public double getSpikeFactor() {
            return spikeFactor;
        }

        public void setSpikeFactor(double spikeFactor) {
            this.spikeFactor = spikeFactor;
        }

        public int getMinimumCurrentPeriodTransactionCount() {
            return minimumCurrentPeriodTransactionCount;
        }

        public void setMinimumCurrentPeriodTransactionCount(int minimumCurrentPeriodTransactionCount) {
            this.minimumCurrentPeriodTransactionCount = minimumCurrentPeriodTransactionCount;
        }

        public boolean isApplyRiskMultipliers() {
            return applyRiskMultipliers;
        }

        public void setApplyRiskMultipliers(boolean applyRiskMultipliers) {
            this.applyRiskMultipliers = applyRiskMultipliers;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    static class Rule011Properties {
        private int currentPeriodDays = 7;
        private int historicalPeriodDays = 30;
        private double spikeFactor = 3.0;
        private int minimumCurrentPeriodTransactionCount = 3;
        private boolean applyRiskMultipliers = true;
        private boolean enabled = true;

        public int getCurrentPeriodDays() {
            return currentPeriodDays;
        }

        public void setCurrentPeriodDays(int currentPeriodDays) {
            this.currentPeriodDays = currentPeriodDays;
        }

        public int getHistoricalPeriodDays() {
            return historicalPeriodDays;
        }

        public void setHistoricalPeriodDays(int historicalPeriodDays) {
            this.historicalPeriodDays = historicalPeriodDays;
        }

        public double getSpikeFactor() {
            return spikeFactor;
        }

        public void setSpikeFactor(double spikeFactor) {
            this.spikeFactor = spikeFactor;
        }

        public int getMinimumCurrentPeriodTransactionCount() {
            return minimumCurrentPeriodTransactionCount;
        }

        public void setMinimumCurrentPeriodTransactionCount(int minimumCurrentPeriodTransactionCount) {
            this.minimumCurrentPeriodTransactionCount = minimumCurrentPeriodTransactionCount;
        }

        public boolean isApplyRiskMultipliers() {
            return applyRiskMultipliers;
        }

        public void setApplyRiskMultipliers(boolean applyRiskMultipliers) {
            this.applyRiskMultipliers = applyRiskMultipliers;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    static class Rule016Properties {
        private int windowHours = 24;
        private int uniqueDebtorThreshold = 8;
        private String minimumWindowVolume = "0.00";
        private boolean applyRiskMultipliers = true;
        private boolean enabled = true;

        public int getWindowHours() {
            return windowHours;
        }

        public void setWindowHours(int windowHours) {
            this.windowHours = windowHours;
        }

        public int getUniqueDebtorThreshold() {
            return uniqueDebtorThreshold;
        }

        public void setUniqueDebtorThreshold(int uniqueDebtorThreshold) {
            this.uniqueDebtorThreshold = uniqueDebtorThreshold;
        }

        public String getMinimumWindowVolume() {
            return minimumWindowVolume;
        }

        public void setMinimumWindowVolume(String minimumWindowVolume) {
            this.minimumWindowVolume = minimumWindowVolume;
        }

        public boolean isApplyRiskMultipliers() {
            return applyRiskMultipliers;
        }

        public void setApplyRiskMultipliers(boolean applyRiskMultipliers) {
            this.applyRiskMultipliers = applyRiskMultipliers;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    static class Rule017Properties {
        private int timeWindowHours = 24;
        private int uniqueCreditorThreshold = 6;
        private int minimumOutgoingTransactionCount = 6;
        private boolean applyRiskMultipliers = true;
        private boolean enabled = true;

        public int getTimeWindowHours() {
            return timeWindowHours;
        }

        public void setTimeWindowHours(int timeWindowHours) {
            this.timeWindowHours = timeWindowHours;
        }

        public int getUniqueCreditorThreshold() {
            return uniqueCreditorThreshold;
        }

        public void setUniqueCreditorThreshold(int uniqueCreditorThreshold) {
            this.uniqueCreditorThreshold = uniqueCreditorThreshold;
        }

        public int getMinimumOutgoingTransactionCount() {
            return minimumOutgoingTransactionCount;
        }

        public void setMinimumOutgoingTransactionCount(int minimumOutgoingTransactionCount) {
            this.minimumOutgoingTransactionCount = minimumOutgoingTransactionCount;
        }

        public boolean isApplyRiskMultipliers() {
            return applyRiskMultipliers;
        }

        public void setApplyRiskMultipliers(boolean applyRiskMultipliers) {
            this.applyRiskMultipliers = applyRiskMultipliers;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    static class Rule018Properties {
        private int historicalWindowDays = 30;
        private double thresholdMultiplier = 5.0;
        private int minimumHistoricalTransactions = 5;
        private String absoluteMinimumAmountFloor = "0.00";
        private boolean applyRiskMultipliers = true;
        private boolean enabled = true;

        public int getHistoricalWindowDays() {
            return historicalWindowDays;
        }

        public void setHistoricalWindowDays(int historicalWindowDays) {
            this.historicalWindowDays = historicalWindowDays;
        }

        public double getThresholdMultiplier() {
            return thresholdMultiplier;
        }

        public void setThresholdMultiplier(double thresholdMultiplier) {
            this.thresholdMultiplier = thresholdMultiplier;
        }

        public int getMinimumHistoricalTransactions() {
            return minimumHistoricalTransactions;
        }

        public void setMinimumHistoricalTransactions(int minimumHistoricalTransactions) {
            this.minimumHistoricalTransactions = minimumHistoricalTransactions;
        }

        public String getAbsoluteMinimumAmountFloor() {
            return absoluteMinimumAmountFloor;
        }

        public void setAbsoluteMinimumAmountFloor(String absoluteMinimumAmountFloor) {
            this.absoluteMinimumAmountFloor = absoluteMinimumAmountFloor;
        }

        public boolean isApplyRiskMultipliers() {
            return applyRiskMultipliers;
        }

        public void setApplyRiskMultipliers(boolean applyRiskMultipliers) {
            this.applyRiskMultipliers = applyRiskMultipliers;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    static class Rule020Properties {
        private int historicalWindowDays = 30;
        private double thresholdMultiplier = 5.0;
        private int minimumHistoricalTransactions = 5;
        private boolean applyRiskMultipliers = true;
        private boolean enabled = true;

        public int getHistoricalWindowDays() {
            return historicalWindowDays;
        }

        public void setHistoricalWindowDays(int historicalWindowDays) {
            this.historicalWindowDays = historicalWindowDays;
        }

        public double getThresholdMultiplier() {
            return thresholdMultiplier;
        }

        public void setThresholdMultiplier(double thresholdMultiplier) {
            this.thresholdMultiplier = thresholdMultiplier;
        }

        public int getMinimumHistoricalTransactions() {
            return minimumHistoricalTransactions;
        }

        public void setMinimumHistoricalTransactions(int minimumHistoricalTransactions) {
            this.minimumHistoricalTransactions = minimumHistoricalTransactions;
        }

        public boolean isApplyRiskMultipliers() {
            return applyRiskMultipliers;
        }

        public void setApplyRiskMultipliers(boolean applyRiskMultipliers) {
            this.applyRiskMultipliers = applyRiskMultipliers;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    static class Rule021Properties {
        private int windowHours = 24;
        private int countThreshold = 8;
        private double tolerancePercent = 0.02;
        private String minimumTotalIncomingVolume = "0.00";
        private boolean applyRiskMultipliers = true;
        private boolean enabled = true;

        public int getWindowHours() {
            return windowHours;
        }

        public void setWindowHours(int windowHours) {
            this.windowHours = windowHours;
        }

        public int getCountThreshold() {
            return countThreshold;
        }

        public void setCountThreshold(int countThreshold) {
            this.countThreshold = countThreshold;
        }

        public double getTolerancePercent() {
            return tolerancePercent;
        }

        public void setTolerancePercent(double tolerancePercent) {
            this.tolerancePercent = tolerancePercent;
        }

        public String getMinimumTotalIncomingVolume() {
            return minimumTotalIncomingVolume;
        }

        public void setMinimumTotalIncomingVolume(String minimumTotalIncomingVolume) {
            this.minimumTotalIncomingVolume = minimumTotalIncomingVolume;
        }

        public boolean isApplyRiskMultipliers() {
            return applyRiskMultipliers;
        }

        public void setApplyRiskMultipliers(boolean applyRiskMultipliers) {
            this.applyRiskMultipliers = applyRiskMultipliers;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    static class Rule024Properties {
        private int windowHours = 48;
        private int pairThreshold = 3;
        private double amountTolerancePercent = 0.05;
        private boolean excludeSameCounterparty = false;
        private boolean applyRiskMultipliers = true;
        private boolean enabled = true;

        public int getWindowHours() {
            return windowHours;
        }

        public void setWindowHours(int windowHours) {
            this.windowHours = windowHours;
        }

        public int getPairThreshold() {
            return pairThreshold;
        }

        public void setPairThreshold(int pairThreshold) {
            this.pairThreshold = pairThreshold;
        }

        public double getAmountTolerancePercent() {
            return amountTolerancePercent;
        }

        public void setAmountTolerancePercent(double amountTolerancePercent) {
            this.amountTolerancePercent = amountTolerancePercent;
        }

        public boolean isExcludeSameCounterparty() {
            return excludeSameCounterparty;
        }

        public void setExcludeSameCounterparty(boolean excludeSameCounterparty) {
            this.excludeSameCounterparty = excludeSameCounterparty;
        }

        public boolean isApplyRiskMultipliers() {
            return applyRiskMultipliers;
        }

        public void setApplyRiskMultipliers(boolean applyRiskMultipliers) {
            this.applyRiskMultipliers = applyRiskMultipliers;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    static class Rule025Properties {
        private int windowHours = 72;
        private int matchThreshold = 3;
        private double amountTolerancePercent = 0.05;
        private boolean applyRiskMultipliers = true;
        private boolean enabled = true;

        public int getWindowHours() {
            return windowHours;
        }

        public void setWindowHours(int windowHours) {
            this.windowHours = windowHours;
        }

        public int getMatchThreshold() {
            return matchThreshold;
        }

        public void setMatchThreshold(int matchThreshold) {
            this.matchThreshold = matchThreshold;
        }

        public double getAmountTolerancePercent() {
            return amountTolerancePercent;
        }

        public void setAmountTolerancePercent(double amountTolerancePercent) {
            this.amountTolerancePercent = amountTolerancePercent;
        }

        public boolean isApplyRiskMultipliers() {
            return applyRiskMultipliers;
        }

        public void setApplyRiskMultipliers(boolean applyRiskMultipliers) {
            this.applyRiskMultipliers = applyRiskMultipliers;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    static class Rule026Properties {
        private int windowHours = 48;
        private int matchThreshold = 3;
        private double amountTolerancePercent = 0.05;
        private double expectedCommissionMinPercent = 0.01;
        private double expectedCommissionMaxPercent = 0.05;
        private double maxCommissionVariance = 0.01;
        private boolean applyRiskMultipliers = true;
        private boolean enabled = true;

        public int getWindowHours() {
            return windowHours;
        }

        public void setWindowHours(int windowHours) {
            this.windowHours = windowHours;
        }

        public int getMatchThreshold() {
            return matchThreshold;
        }

        public void setMatchThreshold(int matchThreshold) {
            this.matchThreshold = matchThreshold;
        }

        public double getAmountTolerancePercent() {
            return amountTolerancePercent;
        }

        public void setAmountTolerancePercent(double amountTolerancePercent) {
            this.amountTolerancePercent = amountTolerancePercent;
        }

        public double getExpectedCommissionMinPercent() {
            return expectedCommissionMinPercent;
        }

        public void setExpectedCommissionMinPercent(double expectedCommissionMinPercent) {
            this.expectedCommissionMinPercent = expectedCommissionMinPercent;
        }

        public double getExpectedCommissionMaxPercent() {
            return expectedCommissionMaxPercent;
        }

        public void setExpectedCommissionMaxPercent(double expectedCommissionMaxPercent) {
            this.expectedCommissionMaxPercent = expectedCommissionMaxPercent;
        }

        public double getMaxCommissionVariance() {
            return maxCommissionVariance;
        }

        public void setMaxCommissionVariance(double maxCommissionVariance) {
            this.maxCommissionVariance = maxCommissionVariance;
        }

        public boolean isApplyRiskMultipliers() {
            return applyRiskMultipliers;
        }

        public void setApplyRiskMultipliers(boolean applyRiskMultipliers) {
            this.applyRiskMultipliers = applyRiskMultipliers;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    static class Rule027Properties {
        private int windowHours = 48;
        private int matchThreshold = 3;
        private double amountTolerancePercent = 0.05;
        private double expectedCommissionPercent = 0.03;
        private double commissionTolerancePercent = 0.015;
        private boolean applyRiskMultipliers = true;
        private boolean enabled = true;

        public int getWindowHours() {
            return windowHours;
        }

        public void setWindowHours(int windowHours) {
            this.windowHours = windowHours;
        }

        public int getMatchThreshold() {
            return matchThreshold;
        }

        public void setMatchThreshold(int matchThreshold) {
            this.matchThreshold = matchThreshold;
        }

        public double getAmountTolerancePercent() {
            return amountTolerancePercent;
        }

        public void setAmountTolerancePercent(double amountTolerancePercent) {
            this.amountTolerancePercent = amountTolerancePercent;
        }

        public double getExpectedCommissionPercent() {
            return expectedCommissionPercent;
        }

        public void setExpectedCommissionPercent(double expectedCommissionPercent) {
            this.expectedCommissionPercent = expectedCommissionPercent;
        }

        public double getCommissionTolerancePercent() {
            return commissionTolerancePercent;
        }

        public void setCommissionTolerancePercent(double commissionTolerancePercent) {
            this.commissionTolerancePercent = commissionTolerancePercent;
        }

        public boolean isApplyRiskMultipliers() {
            return applyRiskMultipliers;
        }

        public void setApplyRiskMultipliers(boolean applyRiskMultipliers) {
            this.applyRiskMultipliers = applyRiskMultipliers;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    static class Rule028Properties {
        private int ageThresholdDays = 30;
        private int velocityWindowHours = 24;
        private int minimumTransactionCount = 3;
        private double volumeSpikeFactor = 3.0;
        private boolean applyRiskMultipliers = true;
        private boolean enabled = true;

        public int getAgeThresholdDays() {
            return ageThresholdDays;
        }

        public void setAgeThresholdDays(int ageThresholdDays) {
            this.ageThresholdDays = ageThresholdDays;
        }

        public int getVelocityWindowHours() {
            return velocityWindowHours;
        }

        public void setVelocityWindowHours(int velocityWindowHours) {
            this.velocityWindowHours = velocityWindowHours;
        }

        public int getMinimumTransactionCount() {
            return minimumTransactionCount;
        }

        public void setMinimumTransactionCount(int minimumTransactionCount) {
            this.minimumTransactionCount = minimumTransactionCount;
        }

        public double getVolumeSpikeFactor() {
            return volumeSpikeFactor;
        }

        public void setVolumeSpikeFactor(double volumeSpikeFactor) {
            this.volumeSpikeFactor = volumeSpikeFactor;
        }

        public boolean isApplyRiskMultipliers() {
            return applyRiskMultipliers;
        }

        public void setApplyRiskMultipliers(boolean applyRiskMultipliers) {
            this.applyRiskMultipliers = applyRiskMultipliers;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    static class Rule030Properties {
        private int lookbackDays = 180;
        private String minimumAmount = "500.00";
        private boolean applyRiskMultipliers = true;
        private boolean enabled = true;

        public int getLookbackDays() {
            return lookbackDays;
        }

        public void setLookbackDays(int lookbackDays) {
            this.lookbackDays = lookbackDays;
        }

        public String getMinimumAmount() {
            return minimumAmount;
        }

        public void setMinimumAmount(String minimumAmount) {
            this.minimumAmount = minimumAmount;
        }

        public boolean isApplyRiskMultipliers() {
            return applyRiskMultipliers;
        }

        public void setApplyRiskMultipliers(boolean applyRiskMultipliers) {
            this.applyRiskMultipliers = applyRiskMultipliers;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    static class Rule044Properties {
        private int currentPeriodDays = 7;
        private int historicalPeriodDays = 30;
        private double countSpikeFactor = 3.0;
        private boolean applyRiskMultipliers = true;
        private boolean enabled = true;

        public int getCurrentPeriodDays() {
            return currentPeriodDays;
        }

        public void setCurrentPeriodDays(int currentPeriodDays) {
            this.currentPeriodDays = currentPeriodDays;
        }

        public int getHistoricalPeriodDays() {
            return historicalPeriodDays;
        }

        public void setHistoricalPeriodDays(int historicalPeriodDays) {
            this.historicalPeriodDays = historicalPeriodDays;
        }

        public double getCountSpikeFactor() {
            return countSpikeFactor;
        }

        public void setCountSpikeFactor(double countSpikeFactor) {
            this.countSpikeFactor = countSpikeFactor;
        }

        public boolean isApplyRiskMultipliers() {
            return applyRiskMultipliers;
        }

        public void setApplyRiskMultipliers(boolean applyRiskMultipliers) {
            this.applyRiskMultipliers = applyRiskMultipliers;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    static class Rule045Properties {
        private int currentPeriodDays = 7;
        private int historicalPeriodDays = 30;
        private double countSpikeFactor = 3.0;
        private boolean applyRiskMultipliers = true;
        private boolean enabled = true;

        public int getCurrentPeriodDays() {
            return currentPeriodDays;
        }

        public void setCurrentPeriodDays(int currentPeriodDays) {
            this.currentPeriodDays = currentPeriodDays;
        }

        public int getHistoricalPeriodDays() {
            return historicalPeriodDays;
        }

        public void setHistoricalPeriodDays(int historicalPeriodDays) {
            this.historicalPeriodDays = historicalPeriodDays;
        }

        public double getCountSpikeFactor() {
            return countSpikeFactor;
        }

        public void setCountSpikeFactor(double countSpikeFactor) {
            this.countSpikeFactor = countSpikeFactor;
        }

        public boolean isApplyRiskMultipliers() {
            return applyRiskMultipliers;
        }

        public void setApplyRiskMultipliers(boolean applyRiskMultipliers) {
            this.applyRiskMultipliers = applyRiskMultipliers;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    static class Rule048Properties {
        private int historicalWindowDays = 30;
        private double thresholdMultiplier = 5.0;
        private int minimumHistoricalTransactions = 5;
        private boolean applyRiskMultipliers = true;
        private boolean enabled = true;

        public int getHistoricalWindowDays() {
            return historicalWindowDays;
        }

        public void setHistoricalWindowDays(int historicalWindowDays) {
            this.historicalWindowDays = historicalWindowDays;
        }

        public double getThresholdMultiplier() {
            return thresholdMultiplier;
        }

        public void setThresholdMultiplier(double thresholdMultiplier) {
            this.thresholdMultiplier = thresholdMultiplier;
        }

        public int getMinimumHistoricalTransactions() {
            return minimumHistoricalTransactions;
        }

        public void setMinimumHistoricalTransactions(int minimumHistoricalTransactions) {
            this.minimumHistoricalTransactions = minimumHistoricalTransactions;
        }

        public boolean isApplyRiskMultipliers() {
            return applyRiskMultipliers;
        }

        public void setApplyRiskMultipliers(boolean applyRiskMultipliers) {
            this.applyRiskMultipliers = applyRiskMultipliers;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    static class Rule054Properties {
        private int historicalWindowDays = 90;
        private int minimumSamples = 100;
        private double madThreshold = 0.06;
        private boolean enabled = true;

        public int getHistoricalWindowDays() {
            return historicalWindowDays;
        }

        public void setHistoricalWindowDays(int historicalWindowDays) {
            this.historicalWindowDays = historicalWindowDays;
        }

        public int getMinimumSamples() {
            return minimumSamples;
        }

        public void setMinimumSamples(int minimumSamples) {
            this.minimumSamples = minimumSamples;
        }

        public double getMadThreshold() {
            return madThreshold;
        }

        public void setMadThreshold(double madThreshold) {
            this.madThreshold = madThreshold;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    static class Rule063Properties {
        private int historicalWindowDays = 90;
        private int minimumSamples = 100;
        private double madThreshold = 0.06;
        private boolean enabled = true;

        public int getHistoricalWindowDays() {
            return historicalWindowDays;
        }

        public void setHistoricalWindowDays(int historicalWindowDays) {
            this.historicalWindowDays = historicalWindowDays;
        }

        public int getMinimumSamples() {
            return minimumSamples;
        }

        public void setMinimumSamples(int minimumSamples) {
            this.minimumSamples = minimumSamples;
        }

        public double getMadThreshold() {
            return madThreshold;
        }

        public void setMadThreshold(double madThreshold) {
            this.madThreshold = madThreshold;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    static class Rule074Properties {
        private double maxPlausibleSpeedKmh = 700.0;
        private long minimumTimeDeltaMinutes = 5;
        private boolean enabled = true;

        public double getMaxPlausibleSpeedKmh() {
            return maxPlausibleSpeedKmh;
        }

        public void setMaxPlausibleSpeedKmh(double maxPlausibleSpeedKmh) {
            this.maxPlausibleSpeedKmh = maxPlausibleSpeedKmh;
        }

        public long getMinimumTimeDeltaMinutes() {
            return minimumTimeDeltaMinutes;
        }

        public void setMinimumTimeDeltaMinutes(long minimumTimeDeltaMinutes) {
            this.minimumTimeDeltaMinutes = minimumTimeDeltaMinutes;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    static class Rule075Properties {
        private int historicalWindowDays = 90;
        private int minimumHistoricalLocations = 5;
        private double distanceThresholdKm = 120.0;
        private boolean applyRiskMultipliers = true;
        private boolean enabled = true;

        public int getHistoricalWindowDays() {
            return historicalWindowDays;
        }

        public void setHistoricalWindowDays(int historicalWindowDays) {
            this.historicalWindowDays = historicalWindowDays;
        }

        public int getMinimumHistoricalLocations() {
            return minimumHistoricalLocations;
        }

        public void setMinimumHistoricalLocations(int minimumHistoricalLocations) {
            this.minimumHistoricalLocations = minimumHistoricalLocations;
        }

        public double getDistanceThresholdKm() {
            return distanceThresholdKm;
        }

        public void setDistanceThresholdKm(double distanceThresholdKm) {
            this.distanceThresholdKm = distanceThresholdKm;
        }

        public boolean isApplyRiskMultipliers() {
            return applyRiskMultipliers;
        }

        public void setApplyRiskMultipliers(boolean applyRiskMultipliers) {
            this.applyRiskMultipliers = applyRiskMultipliers;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    static class Rule076Properties {
        private int inactivityThresholdDays = 90;
        private long burstThresholdMinutes = 60;
        private boolean enabled = true;

        public int getInactivityThresholdDays() {
            return inactivityThresholdDays;
        }

        public void setInactivityThresholdDays(int inactivityThresholdDays) {
            this.inactivityThresholdDays = inactivityThresholdDays;
        }

        public long getBurstThresholdMinutes() {
            return burstThresholdMinutes;
        }

        public void setBurstThresholdMinutes(long burstThresholdMinutes) {
            this.burstThresholdMinutes = burstThresholdMinutes;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    static class Rule078Properties {
        private java.util.List<String> highRiskTypes = java.util.List.of(
            "INTERNATIONAL_WIRE", "CRYPTO_CONVERSION", "CASH_WITHDRAWAL"
        );
        private int historicalWindowDays = 90;
        private int minimumProfileSamples = 5;
        private boolean enabled = true;

        public java.util.List<String> getHighRiskTypes() {
            return highRiskTypes;
        }

        public void setHighRiskTypes(java.util.List<String> highRiskTypes) {
            this.highRiskTypes = highRiskTypes;
        }

        public int getHistoricalWindowDays() {
            return historicalWindowDays;
        }

        public void setHistoricalWindowDays(int historicalWindowDays) {
            this.historicalWindowDays = historicalWindowDays;
        }

        public int getMinimumProfileSamples() {
            return minimumProfileSamples;
        }

        public void setMinimumProfileSamples(int minimumProfileSamples) {
            this.minimumProfileSamples = minimumProfileSamples;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    static class Rule083Properties {
        private int multiAccountThreshold = 3;
        private boolean enabled = true;

        public int getMultiAccountThreshold() {
            return multiAccountThreshold;
        }

        public void setMultiAccountThreshold(int multiAccountThreshold) {
            this.multiAccountThreshold = multiAccountThreshold;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    static class Rule084Properties {
        private int multiAccountThreshold = 3;
        private boolean enabled = true;

        public int getMultiAccountThreshold() {
            return multiAccountThreshold;
        }

        public void setMultiAccountThreshold(int multiAccountThreshold) {
            this.multiAccountThreshold = multiAccountThreshold;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    static class Rule090Properties {
        private int windowDays = 30;
        private int channelThreshold = 3;
        private boolean enabled = true;

        public int getWindowDays() {
            return windowDays;
        }

        public void setWindowDays(int windowDays) {
            this.windowDays = windowDays;
        }

        public int getChannelThreshold() {
            return channelThreshold;
        }

        public void setChannelThreshold(int channelThreshold) {
            this.channelThreshold = channelThreshold;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    static class Rule091Properties {
        private String defaultThreshold = "10000.00";
        private Map<String, String> jurisdictionThresholds = Map.of();
        private boolean enabled = true;

        public String getDefaultThreshold() {
            return defaultThreshold;
        }

        public void setDefaultThreshold(String defaultThreshold) {
            this.defaultThreshold = defaultThreshold;
        }

        public Map<String, String> getJurisdictionThresholds() {
            return jurisdictionThresholds;
        }

        public void setJurisdictionThresholds(Map<String, String> jurisdictionThresholds) {
            this.jurisdictionThresholds = jurisdictionThresholds;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    static class HistoryProperties {
        private String provider = "in-memory";
        private String tableName = "aml_transaction_history";
        private int retentionHours = 24 * 30;
        private int maxTransactionsPerAccount = 50_000;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getTableName() {
            return tableName;
        }

        public void setTableName(String tableName) {
            this.tableName = tableName;
        }

        public int getRetentionHours() {
            return retentionHours;
        }

        public void setRetentionHours(int retentionHours) {
            this.retentionHours = retentionHours;
        }

        public int getMaxTransactionsPerAccount() {
            return maxTransactionsPerAccount;
        }

        public void setMaxTransactionsPerAccount(int maxTransactionsPerAccount) {
            this.maxTransactionsPerAccount = maxTransactionsPerAccount;
        }
    }
}
