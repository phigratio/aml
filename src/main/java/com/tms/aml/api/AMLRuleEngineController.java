package com.tms.aml.api;

import com.tms.aml.api.iso20022.Pacs008Message;
import com.tms.aml.api.iso20022.Pacs008Message.*;
import com.tms.aml.domain.CustomerContext;
import com.tms.aml.domain.CustomerContext.CustomerType;
import com.tms.aml.domain.CustomerContext.RiskRating;
import com.tms.aml.domain.Transaction;
import com.tms.aml.domain.Transaction.TransactionDirection;
import com.tms.aml.engine.AMLRuleEngine;
import com.tms.aml.engine.AMLRuleEngine.TransactionEvaluationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * AML Rule Engine REST API Controller — ISO 20022 pacs.008 Gateway
 *
 * This controller is the single entry-point for external systems (core banking,
 * payment hubs, SWIFT gateways) to submit transactions for AML screening.
 *
 * The API accepts a simplified ISO 20022 pacs.008 (FIToFICustomerCreditTransfer)
 * JSON payload, parses it into the internal domain model (Transaction record +
 * CustomerContext record), and delegates to the AMLRuleEngine which evaluates
 * all registered rules concurrently using Java 25 Virtual Threads.
 *
 * Endpoints:
 *   POST /api/v1/aml/evaluate   — Submit pacs.008 for concurrent rule evaluation
 *   GET  /api/v1/aml/health     — Engine health + rule registry status
 *   GET  /api/v1/aml/config     — Current engine configuration
 *
 * @author AML Engineering Team
 * @since Java 25
 */
@RestController
@RequestMapping("/api/v1/aml")
public class AMLRuleEngineController {

    private static final Logger logger = LoggerFactory.getLogger(AMLRuleEngineController.class);
    private static final long TRANSACTION_EVALUATION_TIMEOUT_MS = 2_000L;

    private final AMLRuleEngine ruleEngine;

    public AMLRuleEngineController(AMLRuleEngine ruleEngine) {
        this.ruleEngine = ruleEngine;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // POST /api/v1/aml/evaluate — PRIMARY EVALUATION ENDPOINT (ISO 20022)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Accepts an ISO 20022 pacs.008 JSON payload, parses it into the AML domain
     * model, and evaluates ALL registered rules concurrently via Virtual Threads.
     *
     * A pacs.008 message is a Credit Transfer — by definition the creditor is
     * receiving funds (direction = CREDIT). The engine evaluates each
     * CdtTrfTxInf block as an independent transaction.
     *
     * If the message contains multiple CdtTrfTxInf entries, each is evaluated
     * independently and the response contains a list of results.
     *
     * @param message ISO 20022 pacs.008 payload
     * @return List of TransactionEvaluationResult (one per CdtTrfTxInf)
     */
    @PostMapping("/evaluate")
    public ResponseEntity<?> evaluateTransaction(@RequestBody Pacs008Message message) {
        try {
            // ─── Validate message envelope ──────────────────────────────────
            if (message.getGrpHdr() == null) {
                return badRequest("grpHdr is required");
            }
            if (message.getCdtTrfTxInf() == null || message.getCdtTrfTxInf().isEmpty()) {
                return badRequest("cdtTrfTxInf must contain at least one transaction");
            }
            if (message.getCustomerContext() == null) {
                return badRequest("customerContext is required");
            }

            // ─── Parse CustomerContext from DTO ─────────────────────────────
            CustomerContext customer = mapCustomerContext(message.getCustomerContext());

            // ─── Parse each CdtTrfTxInf → Transaction and evaluate concurrently ───
            List<TransactionEvaluationResult> results = new ArrayList<>();
            List<Map<String, Object>> errors = new ArrayList<>();
            List<Future<TransactionEvaluationResult>> futures = new ArrayList<>();

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (CdtTrfTxInf txInf : message.getCdtTrfTxInf()) {
                    futures.add(executor.submit(() -> {
                        Transaction transaction = mapTransaction(txInf, message.getGrpHdr());

                        logger.info("ISO 20022 pacs.008 received — msgId={}, txId={}, amount={} {}, creditor={}",
                            message.getGrpHdr().getMsgId(),
                            transaction.transactionId(),
                            transaction.amount(),
                            transaction.currency(),
                            customer.customerId());

                        return ruleEngine.evaluateTransaction(transaction, customer);
                    }));
                }

                for (Future<TransactionEvaluationResult> future : futures) {
                    try {
                        results.add(future.get(TRANSACTION_EVALUATION_TIMEOUT_MS, TimeUnit.MILLISECONDS));
                    } catch (TimeoutException e) {
                        future.cancel(true);
                        errors.add(Map.of(
                            "error", "TRANSACTION_TIMEOUT",
                            "detail", "A transaction evaluation timed out after " + TRANSACTION_EVALUATION_TIMEOUT_MS + " ms"
                        ));
                    } catch (ExecutionException e) {
                        String detail = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                        if (detail == null || detail.isBlank()) {
                            detail = "Unexpected evaluation failure";
                        }
                        errors.add(Map.of(
                            "error", "TRANSACTION_EVALUATION_ERROR",
                            "detail", detail
                        ));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        errors.add(Map.of(
                            "error", "TRANSACTION_INTERRUPTED",
                            "detail", "Transaction evaluation was interrupted"
                        ));
                    }
                }
            }

            // ─── Build response ─────────────────────────────────────────────
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("messageId", message.getGrpHdr().getMsgId());
            response.put("evaluatedAt", Instant.now());
            response.put("requestedTransactionCount", message.getCdtTrfTxInf().size());
            response.put("transactionCount", results.size());
            response.put("errorCount", errors.size());
            response.put("status", errors.isEmpty() ? "SUCCESS" : "PARTIAL_SUCCESS");
            response.put("results", results);
            response.put("errors", errors);

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            logger.warn("Bad request: {}", e.getMessage());
            return badRequest(e.getMessage());
        } catch (Exception e) {
            logger.error("Internal error evaluating pacs.008 message", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Internal evaluation error", "detail", e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GET /api/v1/aml/health
    // ═══════════════════════════════════════════════════════════════════════════

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "UP");
        status.put("service", "AML Rule Engine — Java 25 Virtual Threads");
        status.put("totalRules", ruleEngine.getTotalRuleCount());
        status.put("enabledRules", ruleEngine.getEnabledRuleCount());
        status.put("concurrencyModel", "Virtual Threads (Executors.newVirtualThreadPerTaskExecutor)");
        status.put("timestamp", Instant.now());
        return ResponseEntity.ok(status);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GET /api/v1/aml/config
    // ═══════════════════════════════════════════════════════════════════════════

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("totalRules", ruleEngine.getTotalRuleCount());
        config.put("enabledRules", ruleEngine.getEnabledRuleCount());
        config.put("iso20022PayloadFormat", "pacs.008.001.10 (simplified)");
        return ResponseEntity.ok(config);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ISO 20022 → DOMAIN MODEL MAPPING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Maps a single ISO 20022 CdtTrfTxInf block into our internal Transaction record.
     *
     * Because pacs.008 is a Credit Transfer message the direction is always CREDIT
     * from the creditor's perspective (the creditor's account is being credited).
     */
    private Transaction mapTransaction(CdtTrfTxInf txInf, GrpHdr hdr) {
        // Transaction ID — prefer txId, fall back to endToEndId, then instrId
        String txId = Optional.ofNullable(txInf.getPmtId())
                .map(p -> p.getTxId() != null ? p.getTxId()
                        : p.getEndToEndId() != null ? p.getEndToEndId()
                        : p.getInstrId())
                .orElse("UNKNOWN-" + UUID.randomUUID());

        // Amount + Currency
        BigDecimal amount = txInf.getIntrBkSttlmAmt() != null
                ? txInf.getIntrBkSttlmAmt().getValue()
                : BigDecimal.ZERO;
        String currency = txInf.getIntrBkSttlmAmt() != null
                ? txInf.getIntrBkSttlmAmt().getCcy()
                : "USD";

        // Settlement date → transaction datetime
        LocalDateTime txDateTime = txInf.getIntrBkSttlmDt() != null
                ? txInf.getIntrBkSttlmDt().atStartOfDay()
                : (hdr.getCreDtTm() != null ? hdr.getCreDtTm() : LocalDateTime.now());

        // Creditor (beneficiary) — this is the party receiving funds
        String creditorId = txInf.getCdtr() != null ? txInf.getCdtr().getId() : null;
        String creditorAccount = txInf.getCdtrAcct() != null ? txInf.getCdtrAcct().getId() : null;

        // Debtor (originator) — the counterparty
        String debtorName = txInf.getDbtr() != null ? txInf.getDbtr().getNm() : null;
        String debtorAccount = txInf.getDbtrAcct() != null ? txInf.getDbtrAcct().getId() : null;

        // Purpose / Remittance
        String purpose = "";
        if (txInf.getPurp() != null && txInf.getPurp().getCd() != null) {
            purpose = txInf.getPurp().getCd();
        }
        if (txInf.getRmtInf() != null && txInf.getRmtInf().getUstrd() != null) {
            purpose = purpose.isEmpty()
                    ? txInf.getRmtInf().getUstrd()
                    : purpose + " — " + txInf.getRmtInf().getUstrd();
        }

        return new Transaction(
                txId,
                creditorId != null ? creditorId : "UNKNOWN",
                creditorAccount != null ? creditorAccount : "UNKNOWN",
                amount,
                currency,
                TransactionDirection.CREDIT,  // pacs.008 = credit transfer
                txDateTime,
                debtorName,
                debtorAccount,
                purpose,
                null  // riskScore calculated by the engine
        );
    }

    /**
     * Maps the CustomerContext DTO from the JSON payload into our immutable
     * CustomerContext record.  In production, this data would be fetched from
     * the KYC / CIF repository using the creditor's party ID.
     */
    private CustomerContext mapCustomerContext(CustomerContextDto dto) {
        return new CustomerContext(
                dto.getCustomerId(),
                dto.getAccountNumber(),
                parseCustomerType(dto.getCustomerType()),
                parseRiskRating(dto.getRiskRating()),
                dto.getAccountOpenDate(),
                dto.getKycCompletionDate(),
                dto.getJurisdiction(),
                dto.isPep(),
                dto.isSanctionedStatus(),
                dto.getMonthlyAverageCredit(),
                dto.getMonthlyAverageDebit(),
                dto.getMaxObservedTransaction(),
                dto.getTotalMonthlyTransactionCount(),
                dto.getBusinessSector(),
                null,   // preferredCurrencies — not in DTO
                null    // customAttributes — not in DTO
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ENUM PARSING HELPERS (pattern matching for switch — Java 25)
    // ═══════════════════════════════════════════════════════════════════════════

    private CustomerType parseCustomerType(String raw) {
        if (raw == null) return CustomerType.INDIVIDUAL;
        return switch (raw.toUpperCase()) {
            case "INDIVIDUAL"            -> CustomerType.INDIVIDUAL;
            case "CORPORATE"             -> CustomerType.CORPORATE;
            case "GOVERNMENTAL"          -> CustomerType.GOVERNMENTAL;
            case "FINANCIAL_INSTITUTION" -> CustomerType.FINANCIAL_INSTITUTION;
            default -> CustomerType.INDIVIDUAL;
        };
    }

    private RiskRating parseRiskRating(String raw) {
        if (raw == null) return RiskRating.LOW;
        return switch (raw.toUpperCase()) {
            case "LOW"      -> RiskRating.LOW;
            case "MEDIUM"   -> RiskRating.MEDIUM;
            case "HIGH"     -> RiskRating.HIGH;
            case "CRITICAL" -> RiskRating.CRITICAL;
            default -> RiskRating.LOW;
        };
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UTILITY
    // ═══════════════════════════════════════════════════════════════════════════

    private ResponseEntity<Map<String, String>> badRequest(String detail) {
        return ResponseEntity.badRequest().body(Map.of("error", "Bad Request", "detail", detail));
    }
}
