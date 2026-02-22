package com.tms.aml.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class Pacs008ParallelEvaluationIntegrationTest {

    @Autowired
    private com.tms.aml.engine.AMLRuleEngine ruleEngine;

    private MockMvc mockMvc;
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.standaloneSetup(
            new com.tms.aml.api.AMLRuleEngineController(ruleEngine)
        ).build();
        this.objectMapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    }

    @Test
    void shouldEvaluatePacs008AndReturnScoresForAllRulesInParallel() throws Exception {
        String healthJson = mockMvc.perform(get("/api/v1/aml/health"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        Map<String, Object> health = objectMapper.readValue(healthJson, Map.class);
        int enabledRules = (Integer) health.get("enabledRules");
        assertTrue(enabledRules >= 33);
        assertTrue(((String) health.get("concurrencyModel")).contains("Virtual Threads"));

        Map<String, Object> payload = pacs008Payload();
        String evalJson = mockMvc.perform(post("/api/v1/aml/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        Map<String, Object> body = objectMapper.readValue(evalJson, Map.class);
        assertNotNull(body);
        assertEquals("SUCCESS", body.get("status"));
        assertEquals(2, body.get("transactionCount"));

        List<Map<String, Object>> results = (List<Map<String, Object>>) body.get("results");
        assertEquals(2, results.size());

        for (Map<String, Object> txResult : results) {
            List<Map<String, Object>> allRuleResults = (List<Map<String, Object>>) txResult.get("allRuleResults");
            assertEquals(enabledRules, allRuleResults.size());

            long missingScoreCount = allRuleResults.stream()
                .filter(r -> r.get("severityScore") == null)
                .count();
            assertEquals(0L, missingScoreCount);

            double nonZeroScores = allRuleResults.stream()
                .map(r -> (Number) r.get("severityScore"))
                .filter(n -> n.doubleValue() > 0.0)
                .count();
            assertTrue(nonZeroScores >= 1);
        }
    }

    private Map<String, Object> pacs008Payload() {
        Map<String, Object> grpHdr = new LinkedHashMap<>();
        grpHdr.put("msgId", "MSG-INT-2026-02-22-001");
        grpHdr.put("creDtTm", "2026-02-22T10:00:00");
        grpHdr.put("nbOfTxs", 2);
        grpHdr.put("sttlmInf", Map.of("sttlmMtd", "CLRG"));

        Map<String, Object> tx1 = new LinkedHashMap<>();
        tx1.put("pmtId", Map.of("instrId", "I1", "endToEndId", "E1", "txId", "TX-INT-1"));
        tx1.put("intrBkSttlmAmt", Map.of("value", 15000.00, "ccy", "USD"));
        tx1.put("intrBkSttlmDt", "2026-02-22");
        tx1.put("cdtrAcct", Map.of("id", "ACC-CRED-INT-1"));
        tx1.put("cdtr", Map.of("nm", "Creditor Int", "id", "CUST-INT-1"));
        tx1.put("dbtrAcct", Map.of("id", "ACC-DBTR-INT-1"));
        tx1.put("dbtr", Map.of("nm", "Debtor A", "id", "DBTR-1"));
        tx1.put("rmtInf", Map.of("ustrd", "type=CRYPTO_CONVERSION;channel=CRYPTO;geo=34.0522,-118.2437"));

        Map<String, Object> tx2 = new LinkedHashMap<>();
        tx2.put("pmtId", Map.of("instrId", "I2", "endToEndId", "E2", "txId", "TX-INT-2"));
        tx2.put("intrBkSttlmAmt", Map.of("value", 24000.00, "ccy", "USD"));
        tx2.put("intrBkSttlmDt", "2026-02-22");
        tx2.put("cdtrAcct", Map.of("id", "ACC-CRED-INT-1"));
        tx2.put("cdtr", Map.of("nm", "Creditor Int", "id", "CUST-INT-1"));
        tx2.put("dbtrAcct", Map.of("id", "ACC-DBTR-INT-2"));
        tx2.put("dbtr", Map.of("nm", "Debtor B", "id", "DBTR-2"));
        tx2.put("rmtInf", Map.of("ustrd", "type=INTERNATIONAL_WIRE;channel=BANK;geo=40.7128,-74.0060"));

        Map<String, Object> customerContext = new LinkedHashMap<>();
        customerContext.put("customerId", "CUST-INT-1");
        customerContext.put("accountNumber", "ACC-CRED-INT-1");
        customerContext.put("customerType", "INDIVIDUAL");
        customerContext.put("riskRating", "HIGH");
        customerContext.put("accountOpenDate", "2026-02-10");
        customerContext.put("kycCompletionDate", "2026-02-10");
        customerContext.put("jurisdiction", "US");
        customerContext.put("pep", false);
        customerContext.put("sanctionedStatus", false);
        customerContext.put("monthlyAverageCredit", 2000.00);
        customerContext.put("monthlyAverageDebit", 1200.00);
        customerContext.put("maxObservedTransaction", 25000.00);
        customerContext.put("totalMonthlyTransactionCount", 8);
        customerContext.put("businessSector", "RETAIL");

        return Map.of(
            "grpHdr", grpHdr,
            "cdtTrfTxInf", List.of(tx1, tx2),
            "customerContext", customerContext
        );
    }
}
