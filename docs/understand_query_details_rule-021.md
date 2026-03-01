# Full Breakdown of Rule-021

## What This Rule Does
This rule is the counterpart to Rule-006 and is designed to detect "smurfing" or "structuring" from the sender's perspective. It analyzes the recent outgoing payments from a **creditor** account and counts how many of them are of a similar value (within a configured tolerance) to the most recent transaction. A high count of similar-amount payments *sent* from a single account can be a strong indicator of fraudulent or structured activity.

## SQL Query — Deep Dive
### Parameters Passed In
| Parameter | Value | Purpose |
|---|---|---|
| $1 | `creditorAccount` | The ID of the **creditor** account sending the funds. |
| $2 | `currentPacs002TimeFrame`| The timestamp of the current transaction, used as an upper time limit for the query. |
| $3 | `maxQueryRange` | (Optional) A duration in milliseconds to limit the lookback period. |
| $4 | `tenantId` | For multi-tenant data isolation. |

### CTE 1: all_success
```sql
SELECT
  DISTINCT EndToEndId
FROM
  transaction
WHERE
  source = $1
  AND TxTp = 'pacs.002.001.12'
  AND TxSts = 'ACCC'
  AND CreDtTm::timestamptz <= $2::timestamptz
  AND TenantId = $4
  AND (
    $3::bigint IS NULL
    OR CreDtTm::timestamptz >= $2::timestamptz - ($3::bigint * interval '1 millisecond')
  )
```
**Purpose**: To create a set of `EndToEndId`s for all successfully settled (`ACCC`) outgoing payments from the specified `creditorAccount`, within the optional `maxQueryRange`.

### Final SELECT
```sql
SELECT
  t.Amt AS "Amt"
FROM
  transaction t
  JOIN all_success s USING (EndToEndId)
WHERE
  t.TxTp = 'pacs.008.001.10'
  AND t.TenantId = $4
ORDER BY
  t.CreDtTm::timestamptz DESC;
```
**Purpose**: The main query joins the successful `EndToEndId`s back to the `transaction` table to retrieve the original payment amounts (`Amt`) from the `pacs.008` messages, sorted from newest to oldest.

**Result**: A list of the most recent outgoing transaction amounts for the specified creditor.

---
## Post-Query Logic
The post-query logic is identical to Rule-006.
1. The list of amounts is retrieved from the database.
2. The most recent amount (`amounts[0]`) is used as a reference point.
3. A tolerance window is calculated around this reference amount (e.g., amount ± 5%).
4. The code counts how many of the other recent amounts fall within this window.
5. This final `countOfMatchingAmounts` is the value that gets scored.

---

## ISO 20022 Message Types Used

| Code | Full Name | Role in Rule |
|---|---|---|
| `pacs.008.001.10`| FI To FI Customer Credit Transfer | The source of the actual transaction amount (`Amt`). |
| `pacs.002.001.12`| FI To FI Payment Status Report | Used to identify which transactions were successfully completed. |
| `ACCC` | AcceptedCreditSettlementCompleted | The specific status code that confirms a successful transaction. |

---

## Variable Importance Map
```
creditorAccount         ──► WHO   (The account sending funds, being checked for structuring)
tolerance               ──► HOW SIMILAR (The percentage range for amounts to be 'matching')
countOfMatchingAmounts  ──► COMPUTED COUNT of similarly-valued outgoing transactions
```
The `countOfMatchingAmounts` is the **final output** fed into `determineOutcome()`.

## How to Implement This in Your Application
### Database Table: transaction
```sql
CREATE TABLE transaction (
  id          SERIAL PRIMARY KEY,
  EndToEndId  VARCHAR(35)  NOT NULL,
  source      VARCHAR(50)  NOT NULL, -- The creditor account
  TxTp        VARCHAR(30)  NOT NULL,
  TxSts       VARCHAR(10),
  Amt         NUMERIC(18, 2) NOT NULL,
  CreDtTm     TIMESTAMPTZ  NOT NULL,
  TenantId    VARCHAR(50)  NOT NULL,

  INDEX idx_source_txtp_txsts_tenant (source, TxTp, TxSts, TenantId)
);
```
### Your Rule Config Structure
```json
{
  "config": {
    "parameters": {
      "maxQueryRange": 86400000, // 24 hours
      "tolerance": 0.02  // 2% tolerance
    },
    "exitConditions": [
      { "subRuleRef": ".x00", "reason": "Transaction was not successful" },
      { "subRuleRef": ".x01", "reason": "Insufficient transaction history for analysis (less than 2)" }
    ],
    "bands": [
      { "subRuleRef": ".01", "lowerLimit": 5, "upperLimit": 10 },   // 5-9 matching amounts sent
      { "subRuleRef": ".02", "lowerLimit": 10, "upperLimit": null } // 10+ matching amounts sent
    ]
  }
}
```
### Your determineOutcome Function
```javascript
function determineOutcome(countOfMatchingAmounts, ruleConfig, ruleResult) {
  const band = ruleConfig.config.bands.find(b =>
    countOfMatchingAmounts >= b.lowerLimit &&
    (b.upperLimit === null || countOfMatchingAmounts < b.upperLimit)
  );

  if (!band) {
    return { ...ruleResult, subRuleRef: '.00', reason: 'No significant structuring pattern detected.' };
  }

  return {
    ...ruleResult,
    subRuleRef: band.subRuleRef,
    reason: `Found ${countOfMatchingAmounts} outgoing transactions with similar amounts, indicating potential structuring.`
  };
}
```

### Data Flow Summary
```
Incoming pacs.002 transaction
        │
        ▼
Extract creditorAccount + timestamp
        │
        ▼
Query DB ──► Get amounts of the last several transactions
        │     SENT BY that creditor.
        │
        ├── History < 2 transactions ──► Exit .x01
        │
        └── Has list of amounts ──► In code, count how many amounts are
                                    within 'tolerance' of the newest one.
                                          │
                                          ▼
                                   Match the count against bands
                                          │
                                          ▼
                                   Return risk subRuleRef (.01/.02)
```
### Key Insight
This rule effectively detects structuring from the source. While Rule-006 finds a single account *receiving* many similar payments, Rule-021 finds a single account *sending* many similar payments. This is a classic pattern for a fraudster who has compromised an account and is attempting to "fan out" the funds to multiple different mule accounts in small, uniform chunks to fly under the radar.

Each rule is documented with its business logic, detection algorithm, configurable parameters, required KYC and core banking data inputs, cache requirements, regulatory references, and alert output specifications. and also db design for that specific rule the fields and the variables configurations.
