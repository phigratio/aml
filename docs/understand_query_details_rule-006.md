# Full Breakdown of Rule-006

## What This Rule Does
This rule is designed to detect "structuring" or "smurfing," where multiple small transactions are made to evade the scrutiny that a single large transaction would attract. It works by retrieving the amounts of the most recent transactions for a debtor and then counting how many of them are of a similar value (within a configured tolerance) to the very latest transaction.

## SQL Query — Deep Dive
### Parameters Passed In
| Parameter | Value | Purpose |
|---|---|---|
| $1 | `debtorAccount` | The ID of the debtor account being analyzed for structured payments. |
| $2 | `currentPacs002TimeFrame`| The timestamp of the current transaction, used as the upper time limit for the query. |
| $3 | `maxQueryLimit` | A configuration parameter that limits how many recent transactions are retrieved for analysis. |
| $4 | `tenantId` | For multi-tenant data isolation. |

### CTE 1: all_success
```sql
SELECT
  DISTINCT EndToEndId
FROM
  transaction
WHERE
  destination = $1
  AND TxTp = 'pacs.002.001.12'
  AND TxSts = 'ACCC'
  AND CreDtTm::timestamptz <= $2::timestamptz
  AND TenantId = $4
```
**Purpose**: To create a set of `EndToEndId`s for all successfully settled (`ACCC`) incoming payments to the specified `debtorAccount`. This ensures that only completed transactions are considered in the analysis.

### Final SELECT
```sql
SELECT
  t.Amt AS "Amt"
FROM
  transaction AS t
  JOIN all_success s USING (EndToEndId)
WHERE
  t.TxTp = 'pacs.008.001.10'
  AND t.TenantId = $4
ORDER BY
  t.CreDtTm::timestamptz DESC
LIMIT
  $3::int;
```
**Purpose**: The main query joins the successful `EndToEndId`s back to the `transaction` table to retrieve the original payment amounts (`Amt`) from the `pacs.008` messages. It sorts them from newest to oldest and takes only the top `maxQueryLimit` results.

**Result**: A list of the most recent transaction amounts for the specified debtor.

---
## Post-Query Logic
The core logic of this rule happens in the application code after the query.
1. The list of amounts is retrieved from the database.
2. The most recent amount (`amounts[0]`) is used as a reference point.
3. The `countMatchingAmounts` function calculates a tolerance window (e.g., ±5%) around this reference amount.
4. It then counts how many of the other recent amounts fall within this window.
5. This final count, `matchingAmounts`, is the value that gets scored.

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
debtorAccount     ──► WHO   (The account being analyzed)
maxQueryLimit     ──► HOW MANY (The number of recent transactions to check)
tolerance         ──► HOW SIMILAR (The percentage range for amounts to be considered 'matching')

amounts           ──► RAW RESULT from DB (A list of recent transaction amounts)
matchingAmounts   ──► COMPUTED COUNT of similarly-valued transactions
```
The `matchingAmounts` count is the **final output** fed into `determineOutcome()`.

## Data Requirements

### Configurable Parameters
| Parameter | Type | Description |
|---|---|---|
| `maxQueryLimit` | `number` | The maximum number of recent transactions to retrieve for analysis. |
| `tolerance` | `number` | The percentage (e.g., 0.05 for 5%) used to define the similarity window around the most recent transaction's amount. |

### Required KYC & Core Banking Data
| Field | Path | Description |
|---|---|---|
| `TxSts` | `req.transaction.FIToFIPmtSts.TxInfAndSts.TxSts` | The status of the current transaction, used to check for success (`ACCC`). |
| `CreDtTm` | `req.transaction.FIToFIPmtSts.GrpHdr.CreDtTm` | The creation time of the current transaction. |
| `TenantId` | `req.transaction.TenantId` | The identifier for the tenant. |

### Cache Requirements
| Field | Path | Description |
|---|---|---|
| `dbtrAcctId`| `req.DataCache.dbtrAcctId` | The account ID of the debtor. |

## How to Implement This in Your Application
### Database Table: transaction
```sql
CREATE TABLE transaction (
  id          SERIAL PRIMARY KEY,
  EndToEndId  VARCHAR(35)  NOT NULL,
  source      VARCHAR(50)  NOT NULL,
  destination VARCHAR(50)  NOT NULL, -- The debtor account
  TxTp        VARCHAR(30)  NOT NULL,
  TxSts       VARCHAR(10),
  Amt         NUMERIC(18, 2) NOT NULL,
  CreDtTm     TIMESTAMPTZ  NOT NULL,
  TenantId    VARCHAR(50)  NOT NULL,

  INDEX idx_dest_txtp_txsts_tenant (destination, TxTp, TxSts, TenantId)
);
```
### Your Rule Config Structure
```json
{
  "config": {
    "parameters": {
      "maxQueryLimit": 50,
      "tolerance": 0.05  // 5% tolerance
    },
    "exitConditions": [
      { "subRuleRef": ".x00", "reason": "Transaction was not successful" },
      { "subRuleRef": ".x01", "reason": "Insufficient transaction history for analysis (less than 2)" }
    ],
    "bands": [
      { "subRuleRef": ".01", "lowerLimit": 5, "upperLimit": 10 },   // 5-9 matching amounts
      { "subRuleRef": ".02", "lowerLimit": 10, "upperLimit": null } // 10+ matching amounts
    ]
  }
}
```
### Your determineOutcome Function
```javascript
function determineOutcome(matchingAmounts, ruleConfig, ruleResult) {
  const band = ruleConfig.config.bands.find(b =>
    matchingAmounts >= b.lowerLimit &&
    (b.upperLimit === null || matchingAmounts < b.upperLimit)
  );

  if (!band) {
    // No action needed if the count is below the minimum band
    return { ...ruleResult, subRuleRef: '.00', reason: 'No significant structuring detected' };
  }

  return {
    ...ruleResult,
    subRuleRef: band.subRuleRef,
    reason: `Found ${matchingAmounts} transactions with similar amounts, indicating potential structuring.`
  };
}
```

### Data Flow Summary
```
Incoming pacs.002 transaction
        │
        ▼
Extract debtorAccount + timestamp
        │
        ▼
Query DB ──► Get amounts of the last 'maxQueryLimit' transactions
        │     to that debtor.
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
This rule effectively flags attempts to structure payments. By looking for a cluster of recent transactions with very similar amounts, it can identify coordinated efforts to move funds under the radar. The configurability of the lookback limit (`maxQueryLimit`) and the similarity threshold (`tolerance`) allows the rule to be tuned to specific institutional risks.

Each rule is documented with its business logic, detection algorithm, configurable parameters, required KYC and core banking data inputs, cache requirements, regulatory references, and alert output specifications. and also db design for that specific rule the fields and the variables configurations.
