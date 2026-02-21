package com.tms.aml.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * CustomerContext Record - Immutable KYC/Customer Profile Data
 * 
 * Encapsulates all Know-Your-Customer (KYC) and customer profile information
 * required for comprehensive AML rule evaluation. This data is typically sourced
 * from core banking systems, CRM systems, and persistent KYC repositories.
 * 
 * Regulatory Basis: FATF Recommendations 10-12 (Customer Due Diligence), AFT40
 * Guidelines, and FFIEC AML Examination Manual.
 * 
 * @param customerId Unique customer identifier (CIF - Customer Identification Number)
 * @param accountNumber Primary account number for this customer
 * @param customerType Individual or Corporate customer type
 * @param riskRating Customer risk classification (LOW, MEDIUM, HIGH, CRITICAL)
 * @param accountOpenDate Account opening date - CRITICAL for age-based rules
 * @param kycCompletionDate Date KYC verification was completed
 * @param jurisdiction Country of residence/incorporation
 * @param pep Whether customer is a Politically Exposed Person (PEP) - FATF ML.5
 * @param sanctionedStatus Whether customer is on any sanctions watchlist
 * @param monthlyAverageCredit Baseline monthly average credit amount (for anomaly detection)
 * @param monthlyAverageDebit Baseline monthly average debit amount
 * @param maxObservedTransaction Historical maximum transaction amount
 * @param totalMonthlyTransactionCount Count of transactions in current month
 * @param businessSector NACE/industry code for corporate customers
 * @param preferredCurrencies ISO 4217 codes for expected transaction currencies
 * @param customAttributes Map for extensible KYC attributes (e.g., occupation, source of funds)
 */
public record CustomerContext(
    String customerId,
    String accountNumber,
    CustomerType customerType,
    RiskRating riskRating,
    LocalDate accountOpenDate,
    LocalDate kycCompletionDate,
    String jurisdiction,
    boolean pep,
    boolean sanctionedStatus,
    BigDecimal monthlyAverageCredit,
    BigDecimal monthlyAverageDebit,
    BigDecimal maxObservedTransaction,
    Integer totalMonthlyTransactionCount,
    String businessSector,
    java.util.Set<String> preferredCurrencies,
    Map<String, Object> customAttributes
) {
    
    /**
     * Customer Type Enumeration - Regulatory Classification
     * FFIEC distinguishes between individual and entity customers for different
     * CDD/EDD requirements and risk assessment methodologies.
     */
    public enum CustomerType {
        INDIVIDUAL("Natural Person"),
        CORPORATE("Legal Entity"),
        GOVERNMENTAL("Sovereign or Government Body"),
        FINANCIAL_INSTITUTION("Bank or Licensed Financial Institution");
        
        private final String description;
        
        CustomerType(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    /**
     * Risk Rating Enumeration - Used globally by FATF, FFIEC, and standard-setters
     * Higher risk ratings trigger stricter thresholds in rule evaluation (e.g., Rule 001)
     */
    public enum RiskRating {
        LOW("Low Risk - Standard CDD", 0.2),
        MEDIUM("Medium Risk - Enhanced CDD", 0.5),
        HIGH("High Risk - Full Enhanced Due Diligence", 0.8),
        CRITICAL("Critical Risk - Immediate Investigation Required", 1.0);
        
        private final String description;
        private final double riskMultiplier;
        
        RiskRating(String description, double riskMultiplier) {
            this.description = description;
            this.riskMultiplier = riskMultiplier;
        }
        
        public String getDescription() {
            return description;
        }
        
        /**
         * Returns a multiplier to adjust rule thresholds based on customer risk
         * Example: HIGH risk customer -> stricter (lower) threshold for Rule 001
         */
        public double getRiskMultiplier() {
            return riskMultiplier;
        }
    }
    
    /**
     * Compact constructor for validation (Java Record feature)
     */
    public CustomerContext {
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("Customer ID cannot be null or blank");
        }
        if (accountNumber == null || accountNumber.isBlank()) {
            throw new IllegalArgumentException("Account number cannot be null or blank");
        }
        if (accountOpenDate == null) {
            throw new IllegalArgumentException("Account open date cannot be null");
        }
        if (riskRating == null) {
            throw new IllegalArgumentException("Risk rating cannot be null");
        }
    }
    
    /**
     * Calculate account age in days from opening date to a given date
     * CRITICAL METHOD for Rule 001 evaluation
     * 
     * @param asOf Reference date (typically transaction date)
     * @return Number of days account has been open
     */
    public long accountAgeDays(java.time.LocalDate asOf) {
        return java.time.temporal.ChronoUnit.DAYS.between(this.accountOpenDate, asOf);
    }
    
    /**
     * Determine if account is newly opened based on configurable threshold
     * Default: 30 days (per FFIEC and FATF guidelines)
     * 
     * @param ageThresholdDays Threshold in days
     * @param asOfDate Reference date
     * @return true if account age is less than threshold
     */
    public boolean isNewlyOpened(int ageThresholdDays, java.time.LocalDate asOfDate) {
        return accountAgeDays(asOfDate) < ageThresholdDays;
    }
    
    /**
     * Check if customer is high-risk or higher
     * Used to apply stricter evaluation criteria
     */
    public boolean isHighRiskOrCritical() {
        return this.riskRating == RiskRating.HIGH || this.riskRating == RiskRating.CRITICAL;
    }
    
    /**
     * Check if customer is PEP or Sanctioned (FATF ML.5 / ER.6)
     * Typically triggers full enhanced due diligence
     */
    public boolean requiresEnhancedDueDiligence() {
        return this.pep || this.sanctionedStatus;
    }
}
