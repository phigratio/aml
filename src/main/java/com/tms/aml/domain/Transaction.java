package com.tms.aml.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Transaction Record - Immutable Data Structure for AML Transaction Processing
 * 
 * Represents a simplified ISO 20022 pacs.008 transaction payload adapted for
 * high-throughput AML rule evaluation. This record encapsulates all essential
 * transaction metadata required for concurrent AML rule processing.
 * 
 * ISO 20022 Context: pacs.008 (FIToFICustomerCreditTransfer) is the standard
 * format for customer credit transfers. This record extracts the AML-relevant fields.
 * 
 * @param transactionId Unique identifier (ISO 20022: msgId or endToEndId)
 * @param customerId Customer/Account holder identifier
 * @param accountNumber Source/Destination account number
 * @param amount Transaction amount in base currency
 * @param currency ISO 4217 currency code (e.g., USD, TZS)
 * @param direction Transaction direction: CREDIT (inbound) or DEBIT (outbound)
 * @param transactionDate Timestamp of transaction initiation (ISO 8601)
 * @param counterpartyName Name of the counterparty (originator or beneficiary)
 * @param counterpartyAccount Counterparty account identifier
 * @param transactionPurpose Purpose code or narrative (ISO 20022: purp or ustrd)
 * @param riskScore Pre-calculated risk score (0.0-1.0) from upstream processors
 */
public record Transaction(
    String transactionId,
    String customerId,
    String accountNumber,
    BigDecimal amount,
    String currency,
    TransactionDirection direction,
    LocalDateTime transactionDate,
    String counterpartyName,
    String counterpartyAccount,
    String transactionPurpose,
    Double riskScore
) {
    
    /**
     * Enumeration for transaction direction based on customer perspective.
     * CREDIT: Inbound to customer account (high risk for money mules / placement)
     * DEBIT: Outbound from customer account (layering indicator)
     */
    public enum TransactionDirection {
        CREDIT("Inbound - Credit to Account"),
        DEBIT("Outbound - Debit from Account");
        
        private final String description;
        
        TransactionDirection(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    /**
     * Compact constructor for validation (Java Record feature)
     * Ensures business rules and data integrity at instantiation
     */
    public Transaction {
        if (transactionId == null || transactionId.isBlank()) {
            throw new IllegalArgumentException("Transaction ID cannot be null or blank");
        }
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("Customer ID cannot be null or blank");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Transaction amount must be positive");
        }
        if (direction == null) {
            throw new IllegalArgumentException("Transaction direction cannot be null");
        }
        if (transactionDate == null) {
            throw new IllegalArgumentException("Transaction date cannot be null");
        }
        if (riskScore != null && (riskScore < 0.0 || riskScore > 1.0)) {
            throw new IllegalArgumentException("Risk score must be between 0.0 and 1.0");
        }
    }
    
    /**
     * Convenience method to check if this is a credit transaction
     * Used extensively in AML rule evaluation
     */
    public boolean isCredit() {
        return this.direction == TransactionDirection.CREDIT;
    }
    
    /**
     * Convenience method to check if this is a debit transaction
     */
    public boolean isDebit() {
        return this.direction == TransactionDirection.DEBIT;
    }
    
    /**
     * Determine if transaction amount exceeds threshold
     * Useful for high-value transaction rules
     * 
     * @param threshold Amount threshold
     * @return true if transaction amount exceeds threshold
     */
    public boolean exceedsAmount(BigDecimal threshold) {
        return this.amount.compareTo(threshold) > 0;
    }
}
