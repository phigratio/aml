package com.tms.aml.api.iso20022;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * ISO 20022 pacs.008 — FIToFICustomerCreditTransfer (Simplified)
 *
 * This is a simplified Java representation of the ISO 20022 pacs.008.001.10
 * message structure, trimmed to the fields needed by the AML Rule Engine.
 * The full pacs.008 schema contains hundreds of fields; here we model only
 * the AML-critical subset so that external core-banking or payment-hub
 * systems can POST transactions in a standards-aligned JSON format.
 *
 * Reference: ISO 20022 Message Definition Report — pacs.008.001.10
 *            https://www.iso20022.org/catalogue-messages/iso-20022-messages-archive
 *
 * JSON example accepted by POST /api/v1/aml/evaluate:
 * {
 *   "grpHdr": {
 *     "msgId": "MSG-2026-02-22-001",
 *     "creDtTm": "2026-02-22T04:18:40",
 *     "nbOfTxs": 1,
 *     "sttlmInf": { "sttlmMtd": "CLRG" }
 *   },
 *   "cdtTrfTxInf": [
 *     {
 *       "pmtId": {
 *         "instrId": "INSTR-001",
 *         "endToEndId": "E2E-001",
 *         "txId": "TXN-001"
 *       },
 *       "intrBkSttlmAmt": { "value": 15000.00, "ccy": "USD" },
 *       "intrBkSttlmDt": "2026-02-22",
 *       "cdtrAcct": { "id": "ACC-CRED-001" },
 *       "cdtr": {
 *         "nm": "John Doe",
 *         "id": "CUST-001"
 *       },
 *       "dbtrAcct": { "id": "ACC-DBT-001" },
 *       "dbtr": {
 *         "nm": "Jane Smith",
 *         "id": "CUST-002"
 *       },
 *       "purp": { "cd": "GDDS" },
 *       "rmtInf": { "ustrd": "Payment for goods" }
 *     }
 *   ],
 *   "customerContext": {
 *     "customerId": "CUST-001",
 *     "accountNumber": "ACC-CRED-001",
 *     "customerType": "INDIVIDUAL",
 *     "riskRating": "HIGH",
 *     "accountOpenDate": "2026-02-12",
 *     "pep": false,
 *     "sanctionedStatus": false,
 *     "monthlyAverageCredit": 2000.00,
 *     "monthlyAverageDebit": 1000.00,
 *     "jurisdiction": "TZ"
 *   }
 * }
 */
public class Pacs008Message {

    // ═══════════════════════════════════════════════════════════════════
    // GroupHeader — GrpHdr (ISO 20022: GroupHeader96)
    // ═══════════════════════════════════════════════════════════════════

    private GrpHdr grpHdr;

    // ═══════════════════════════════════════════════════════════════════
    // CreditTransferTransactionInformation — CdtTrfTxInf
    // (ISO 20022: CreditTransferTransaction50, repeatable)
    // ═══════════════════════════════════════════════════════════════════

    private List<CdtTrfTxInf> cdtTrfTxInf;

    // ═══════════════════════════════════════════════════════════════════
    // AML Extension — CustomerContext (not part of ISO 20022 but
    // required by the AML engine; in production this would be a
    // separate KYC/CIF lookup keyed on the creditor party ID)
    // ═══════════════════════════════════════════════════════════════════

    private CustomerContextDto customerContext;

    // ────────── getters / setters ──────────

    public GrpHdr getGrpHdr() { return grpHdr; }
    public void setGrpHdr(GrpHdr grpHdr) { this.grpHdr = grpHdr; }

    public List<CdtTrfTxInf> getCdtTrfTxInf() { return cdtTrfTxInf; }
    public void setCdtTrfTxInf(List<CdtTrfTxInf> cdtTrfTxInf) { this.cdtTrfTxInf = cdtTrfTxInf; }

    public CustomerContextDto getCustomerContext() { return customerContext; }
    public void setCustomerContext(CustomerContextDto customerContext) { this.customerContext = customerContext; }

    // ═══════════════════════════════════════════════════════════════════
    // NESTED CLASSES (mirror ISO 20022 element hierarchy)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * GroupHeader96 — Message-level header.
     */
    public static class GrpHdr {
        /** MessageIdentification — unique message ID assigned by the sender */
        private String msgId;
        /** CreationDateTime — message creation timestamp */
        private LocalDateTime creDtTm;
        /** NumberOfTransactions — count of CdtTrfTxInf blocks */
        private int nbOfTxs;
        /** SettlementInformation */
        private SttlmInf sttlmInf;

        public String getMsgId() { return msgId; }
        public void setMsgId(String msgId) { this.msgId = msgId; }
        public LocalDateTime getCreDtTm() { return creDtTm; }
        public void setCreDtTm(LocalDateTime creDtTm) { this.creDtTm = creDtTm; }
        public int getNbOfTxs() { return nbOfTxs; }
        public void setNbOfTxs(int nbOfTxs) { this.nbOfTxs = nbOfTxs; }
        public SttlmInf getSttlmInf() { return sttlmInf; }
        public void setSttlmInf(SttlmInf sttlmInf) { this.sttlmInf = sttlmInf; }
    }

    /** SettlementInformation (simplified) */
    public static class SttlmInf {
        private String sttlmMtd;
        public String getSttlmMtd() { return sttlmMtd; }
        public void setSttlmMtd(String sttlmMtd) { this.sttlmMtd = sttlmMtd; }
    }

    /**
     * CreditTransferTransaction50 — per-transaction block.
     * One pacs.008 message may carry many of these; for AML we
     * evaluate each independently and concurrently.
     */
    public static class CdtTrfTxInf {
        /** PaymentIdentification */
        private PmtId pmtId;
        /** InterbankSettlementAmount — the actual transfer value + currency */
        private Amount intrBkSttlmAmt;
        /** InterbankSettlementDate */
        private LocalDate intrBkSttlmDt;
        /** CreditorAccount */
        private Account cdtrAcct;
        /** Creditor party */
        private Party cdtr;
        /** DebtorAccount */
        private Account dbtrAcct;
        /** Debtor party */
        private Party dbtr;
        /** Purpose (ISO 20022 purpose code) */
        private Purpose purp;
        /** RemittanceInformation — free-text payment details */
        private RmtInf rmtInf;

        // getters/setters
        public PmtId getPmtId() { return pmtId; }
        public void setPmtId(PmtId pmtId) { this.pmtId = pmtId; }
        public Amount getIntrBkSttlmAmt() { return intrBkSttlmAmt; }
        public void setIntrBkSttlmAmt(Amount intrBkSttlmAmt) { this.intrBkSttlmAmt = intrBkSttlmAmt; }
        public LocalDate getIntrBkSttlmDt() { return intrBkSttlmDt; }
        public void setIntrBkSttlmDt(LocalDate intrBkSttlmDt) { this.intrBkSttlmDt = intrBkSttlmDt; }
        public Account getCdtrAcct() { return cdtrAcct; }
        public void setCdtrAcct(Account cdtrAcct) { this.cdtrAcct = cdtrAcct; }
        public Party getCdtr() { return cdtr; }
        public void setCdtr(Party cdtr) { this.cdtr = cdtr; }
        public Account getDbtrAcct() { return dbtrAcct; }
        public void setDbtrAcct(Account dbtrAcct) { this.dbtrAcct = dbtrAcct; }
        public Party getDbtr() { return dbtr; }
        public void setDbtr(Party dbtr) { this.dbtr = dbtr; }
        public Purpose getPurp() { return purp; }
        public void setPurp(Purpose purp) { this.purp = purp; }
        public RmtInf getRmtInf() { return rmtInf; }
        public void setRmtInf(RmtInf rmtInf) { this.rmtInf = rmtInf; }
    }

    /** PaymentIdentification — IDs per transaction */
    public static class PmtId {
        private String instrId;
        private String endToEndId;
        private String txId;

        public String getInstrId() { return instrId; }
        public void setInstrId(String instrId) { this.instrId = instrId; }
        public String getEndToEndId() { return endToEndId; }
        public void setEndToEndId(String endToEndId) { this.endToEndId = endToEndId; }
        public String getTxId() { return txId; }
        public void setTxId(String txId) { this.txId = txId; }
    }

    /** ActiveCurrencyAndAmount */
    public static class Amount {
        private BigDecimal value;
        private String ccy;

        public BigDecimal getValue() { return value; }
        public void setValue(BigDecimal value) { this.value = value; }
        public String getCcy() { return ccy; }
        public void setCcy(String ccy) { this.ccy = ccy; }
    }

    /** CashAccount — simplified to just an ID */
    public static class Account {
        private String id;
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
    }

    /** Party — originator or beneficiary */
    public static class Party {
        private String nm;
        private String id;
        public String getNm() { return nm; }
        public void setNm(String nm) { this.nm = nm; }
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
    }

    /** Purpose code */
    public static class Purpose {
        private String cd;
        public String getCd() { return cd; }
        public void setCd(String cd) { this.cd = cd; }
    }

    /** RemittanceInformation (unstructured text) */
    public static class RmtInf {
        private String ustrd;
        public String getUstrd() { return ustrd; }
        public void setUstrd(String ustrd) { this.ustrd = ustrd; }
    }

    // ═══════════════════════════════════════════════════════════════════
    // CustomerContext DTO — AML-specific extension
    // In production this would come from a KYC/CIF repository lookup,
    // but we accept it inline so the API is self-contained for demo.
    // ═══════════════════════════════════════════════════════════════════

    public static class CustomerContextDto {
        private String customerId;
        private String accountNumber;
        private String customerType;      // INDIVIDUAL | CORPORATE | …
        private String riskRating;        // LOW | MEDIUM | HIGH | CRITICAL
        private LocalDate accountOpenDate;
        private LocalDate kycCompletionDate;
        private String jurisdiction;
        private boolean pep;
        private boolean sanctionedStatus;
        private BigDecimal monthlyAverageCredit;
        private BigDecimal monthlyAverageDebit;
        private BigDecimal maxObservedTransaction;
        private Integer totalMonthlyTransactionCount;
        private String businessSector;

        // getters/setters
        public String getCustomerId() { return customerId; }
        public void setCustomerId(String customerId) { this.customerId = customerId; }
        public String getAccountNumber() { return accountNumber; }
        public void setAccountNumber(String accountNumber) { this.accountNumber = accountNumber; }
        public String getCustomerType() { return customerType; }
        public void setCustomerType(String customerType) { this.customerType = customerType; }
        public String getRiskRating() { return riskRating; }
        public void setRiskRating(String riskRating) { this.riskRating = riskRating; }
        public LocalDate getAccountOpenDate() { return accountOpenDate; }
        public void setAccountOpenDate(LocalDate accountOpenDate) { this.accountOpenDate = accountOpenDate; }
        public LocalDate getKycCompletionDate() { return kycCompletionDate; }
        public void setKycCompletionDate(LocalDate kycCompletionDate) { this.kycCompletionDate = kycCompletionDate; }
        public String getJurisdiction() { return jurisdiction; }
        public void setJurisdiction(String jurisdiction) { this.jurisdiction = jurisdiction; }
        public boolean isPep() { return pep; }
        public void setPep(boolean pep) { this.pep = pep; }
        public boolean isSanctionedStatus() { return sanctionedStatus; }
        public void setSanctionedStatus(boolean sanctionedStatus) { this.sanctionedStatus = sanctionedStatus; }
        public BigDecimal getMonthlyAverageCredit() { return monthlyAverageCredit; }
        public void setMonthlyAverageCredit(BigDecimal monthlyAverageCredit) { this.monthlyAverageCredit = monthlyAverageCredit; }
        public BigDecimal getMonthlyAverageDebit() { return monthlyAverageDebit; }
        public void setMonthlyAverageDebit(BigDecimal monthlyAverageDebit) { this.monthlyAverageDebit = monthlyAverageDebit; }
        public BigDecimal getMaxObservedTransaction() { return maxObservedTransaction; }
        public void setMaxObservedTransaction(BigDecimal maxObservedTransaction) { this.maxObservedTransaction = maxObservedTransaction; }
        public Integer getTotalMonthlyTransactionCount() { return totalMonthlyTransactionCount; }
        public void setTotalMonthlyTransactionCount(Integer totalMonthlyTransactionCount) { this.totalMonthlyTransactionCount = totalMonthlyTransactionCount; }
        public String getBusinessSector() { return businessSector; }
        public void setBusinessSector(String businessSector) { this.businessSector = businessSector; }
    }
}
