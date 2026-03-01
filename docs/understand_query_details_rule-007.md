# Full Breakdown of Rule-007

## What This Rule Does
This rule detects potentially automated or fraudulent activity by analyzing the free-text remittance information of a debtor's two most recent transactions. It calculates the **Levenshtein distance**—a measure of the textual difference between two strings—for the unstructured remittance (`Ustrd`) fields. A very low distance (i.e., nearly identical messages) between two separate, recent transactions can be a strong indicator of automated, and possibly illicit, behavior.

## Multi-Query Logic
This rule executes two sequential database queries to get the data it needs.

### Query 1: Find Last Two Transactions
```sql
SELECT
  EndToEndId AS "EndToEndId"
FROM
  transaction
WHERE
  destination = $1
  AND TxTp = 'pacs.002.001.12'
  AND TxSts = 'ACCC'
  AND CreDtTm::timestamptz <= $2::timestamptz
  AND TenantId = $3
ORDER BY
  CreDtTm::timestamptz DESC
LIMIT
  2;
```
*   **Purpose**: To get the `EndToEndId`s of the two most recent, successfully settled (`ACCC`) transactions for a specific `debtorAccount`. This query hits the indexed `transaction` table for performance.
*   **Parameters**:
    *   `$1`: `debtorAccount`
    *   `$2`: `currentPacs002TimeFrame`
    *   `$3`: `tenantId`

### Query 2: Retrieve Remittance Text
```sql
SELECT
  document -> 'FIToFICstmrCdtTrf' -> 'RmtInf' ->> 'Ustrd' AS "Ustrd"
FROM
  pacs008
WHERE
  (
    document -> 'FIToFICstmrCdtTrf' -> 'CdtTrfTxInf' -> 'PmtId' ->> 'EndToEndId'
  ) = ANY($1::text[])
  AND tenantId = $2;
```
*   **Purpose**: Using the `EndToEndId`s from the first query, this retrieves the full, original `pacs.008` JSON documents from the `pacs008` (raw history) table. It then uses JSON operators (`->`, `->>`) to extract the unstructured remittance string (`Ustrd`).
*   **Parameters**:
    *   `$1`: An array of the two `EndToEndId`s from Query 1.
    *   `$2`: `tenantId`.

---
## Post-Query Logic
After the queries, the application code performs the core analysis:
1.  It takes the two `Ustrd` strings returned from Query 2.
2.  It uses the `fast-levenshtein` library to compute the distance between them.
3.  `levenshtein.get('string1', 'string2')` returns an integer. A value of `0` means the strings are identical. A low value (e.g., 1-5) means they are very similar.
4.  This integer distance is the final value passed to `determineOutcome`.

---

## ISO 20022 Message Types Used

| Code | Full Name | Role in Rule |
|---|---|---|
| `pacs.008.001.10`| FI To FI Customer Credit Transfer | The raw message stored in the `pacs008` table which contains the `RmtInf.Ustrd` field. |
| `pacs.002.001.12`| FI To FI Payment Status Report | Used to efficiently find the most recent *successful* transactions to analyze. |
| `ACCC` | AcceptedCreditSettlementCompleted | The status code confirming a successful transaction. |

---

## Variable Importance Map
```
debtorAccount       ──► WHO   (The account whose payment messages are being analyzed)
Ustrd               ──► WHAT  (The unstructured remittance text being compared)
levenshteinDistance ──► COMPUTED SCORE of how different the two messages are
```
A **low** `levenshteinDistance` indicates a **high** risk.

## How to Implement This in Your Application
### Database Tables
This rule requires two tables: one for indexed event history and one for raw document storage.

**`transaction` (Event History)**
```sql
CREATE TABLE transaction (
  id          SERIAL PRIMARY KEY,
  EndToEndId  VARCHAR(35)  NOT NULL,
  destination VARCHAR(50)  NOT NULL, -- The debtor account
  TxTp        VARCHAR(30)  NOT NULL,
  TxSts       VARCHAR(10),
  CreDtTm     TIMESTAMPTZ  NOT NULL,
  TenantId    VARCHAR(50)  NOT NULL,
  INDEX idx_dest_txtp_credtm_tenant (destination, TxTp, CreDtTm, TenantId)
);
```
**`pacs008` (Raw Document History)**
```sql
CREATE TABLE pacs008 (
  id          SERIAL PRIMARY KEY,
  EndToEndId  VARCHAR(35)  NOT NULL,
  document    JSONB, -- The full pacs.008 message
  TenantId    VARCHAR(50)  NOT NULL,
  -- A GIN index is highly recommended for querying JSONB fields
  INDEX idx_pacs008_doc_e2eid ON pacs008 USING GIN ((document -> 'FIToFICstmrCdtTrf' -> 'CdtTrfTxInf' -> 'PmtId' ->> 'EndToEndId'));
);
```

### Your Rule Config Structure
```json
{
  "config": {
    "exitConditions": [
      { "subRuleRef": ".x00", "reason": "Transaction was not successful" },
      { "subRuleRef": ".x01", "reason": "Insufficient transaction history for analysis (less than 2)" }
    ],
    "bands": [
      { "subRuleRef": ".01", "lowerLimit": 0, "upperLimit": 2 },   // 0-1 character difference (very high risk)
      { "subRuleRef": ".02", "lowerLimit": 2, "upperLimit": 5 }    // 2-4 character difference (high risk)
    ]
  }
}
```
### Your determineOutcome Function
```javascript
function determineOutcome(levenshteinDistance, ruleConfig, ruleResult) {
  const band = ruleConfig.config.bands.find(b =>
    levenshteinDistance >= b.lowerLimit &&
    levenshteinDistance < b.upperLimit
  );

  if (!band) {
    return { ...ruleResult, subRuleRef: '.00', reason: 'Remittance info appears sufficiently unique.' };
  }

  return {
    ...ruleResult,
    subRuleRef: band.subRuleRef,
    reason: `Remittance info is highly similar to previous transaction (Levenshtein distance: ${levenshteinDistance}).`
  };
}
```

### Data Flow Summary
```
Incoming pacs.002 transaction
        │
        ▼
Query `transaction` table ──► Get EndToEndId of last 2 successful txns
        │
        ├── History < 2 ──► Exit .x01
        │
        └── Has 2 IDs ──► Query `pacs008` table with those IDs
                                │
                                ▼
                         Extract 'Ustrd' text from each of the 2 JSON documents
                                │
                                ▼
                         Calculate Levenshtein distance between the two texts
                                │
                                ▼
                         Match distance against bands
                                │
                                ▼
                         Return risk subRuleRef (.01/.02)
```
### Key Insight
This rule is effective at catching fraud patterns that rely on automation. Legitimate, human-initiated payments rarely have identical or nearly identical remittance notes. A low Levenshtein score strongly implies that the payments were generated by a script using a template, a common tactic for testing stolen accounts or other automated fraudulent schemes.

Each rule is documented with its business logic, detection algorithm, configurable parameters, required KYC and core banking data inputs, cache requirements, regulatory references, and alert output specifications. and also db design for that specific rule the fields and the variables configurations.
