# Full Breakdown of Rule-024

## What This Rule Does
This rule detects a pattern known as "transaction mirroring" or "pass-through" activity, focusing on the **creditor** account. It identifies if a recent outgoing payment from the creditor was preceded by a series of smaller incoming payments that sum up to the same amount (within a tolerance). This can indicate that the account is being used as a temporary conduit to launder or move funds quickly.

## SQL Query — Deep Dive
This rule uses a complex query to isolate the target outgoing payment and the set of prior incoming payments.

### Parameters Passed In
| Parameter | Value | Purpose |
|---|---|---|
| $1 | `creditorAccountId` | The ID of the creditor account to analyze. |
| $2 | `req.DataCache.creDtTm` | The timestamp of the target outgoing transaction. |
| $3 | `maxQueryRange` | The lookback period in milliseconds to find the incoming transactions. |
| $4 | `currentPacs002TimeFrame` | The timestamp of the *current* transaction being processed (used for ordering). |
| $5 | `tenantId` | For multi-tenant data isolation. |

### CTE 1: newest
```sql
SELECT EndToEndId, Amt, CreDtTm::timestamptz
FROM transaction
WHERE source = $1 AND TxTp = 'pacs.008.001.10' AND CreDtTm::timestamptz < $2::timestamptz
ORDER BY CreDtTm::timestamptz DESC LIMIT 1
```
**Purpose**: To identify the single most recent outgoing payment (`targetAmount`) made by the creditor before the one specified in the data cache.

### CTE 2: all_success
```sql
SELECT DISTINCT t.EndToEndId
FROM transaction t JOIN newest n ON TRUE
WHERE t.source = $1 AND t.TxTp = 'pacs.002.001.12' AND t.TxSts = 'ACCC'
  AND t.CreDtTm::timestamptz < n.CreDtTm::timestamptz
  AND t.CreDtTm::timestamptz >= n.CreDtTm::timestamptz - ($3::bigint * interval '1 millisecond')
```
**Purpose**: To find all successful incoming transactions to the creditor that occurred *before* the target outgoing payment (`newest`) and within the `maxQueryRange`.

### CTE 3: hist
```sql
SELECT t.Amt AS amt, t.CreDtTm::timestamptz
FROM transaction t JOIN all_success s USING (EndToEndId)
WHERE t.TxTp = 'pacs.008.001.10'
ORDER BY t.CreDtTm::timestamptz DESC
```
**Purpose**: To retrieve the amounts of the historical incoming payments identified in `all_success`.

### Final SELECT
**Purpose**: The query returns two main values:
1.  `targetAmount`: The amount of the single outgoing payment being mirrored.
2.  `historicalAmounts`: An array of the prior incoming payment amounts that will be summed up.

---
## Post-Query Logic
The `amountTracking` function iterates through the `historicalAmounts`, summing them up one by one. If at any point the running total closely matches the `targetAmount` (within the configured `tolerance`), it returns the number of transactions it took to reach that sum. This number is the final value passed to `determineOutcome`.

---
## Variable Importance Map
```
creditorAccountId  ──► WHO (The account being used as a pass-through)
targetAmount       ──► WHAT (The outgoing payment being mirrored)
historicalAmounts  ──► HOW (The prior incoming payments that make up the mirror)
iterationValue     ──► COMPUTED COUNT (Number of txns needed to mirror the amount)
```

## Data Requirements

### Configurable Parameters
| Parameter | Type | Description |
|---|---|---|
| `tolerance` | `number` | The percentage (e.g., 0.01 for 1%) used for fuzzy matching of the summed amounts. |
| `maxQueryRange`| `number` | The lookback period in milliseconds to find prior incoming payments. |

### Required KYC & Core Banking Data
| Field | Path | Description |
|---|---|---|
| `TxSts` | `req.transaction.FIToFIPmtSts.TxInfAndSts.TxSts` | The status of the current transaction. |
| `CreDtTm` | `req.transaction.FIToFIPmtSts.GrpHdr.CreDtTm` | The creation time of the current transaction. |
| `TenantId` | `req.transaction.TenantId` | The identifier for the tenant. |

### Cache Requirements
| Field | Path | Description |
|---|---|---|
| `cdtrAcctId`| `req.DataCache.cdtrAcctId` | The account ID of the creditor. |
| `creDtTm` | `req.DataCache.creDtTm` | The timestamp of the specific outgoing transaction to be used as the target. |

## How to Implement This in Your Application
### Your Rule Config Structure
```json
{
  "config": {
    "parameters": {
      "maxQueryRange": 604800000, // 7 days
      "tolerance": 0.01 // 1%
    },
    "exitConditions": [
      { "subRuleRef": ".x01", "reason": "Insufficient transaction history" },
      { "subRuleRef": ".x03", "reason": "No transaction mirroring detected" }
    ],
    "bands": [
      { "subRuleRef": ".01", "lowerLimit": 2, "upperLimit": 4 }, // Mirrored by 2-3 prior txns
      { "subRuleRef": ".02", "lowerLimit": 4, "upperLimit": null } // Mirrored by 4+ prior txns
    ]
  }
}
```
### Key Insight
This rule detects when an account is being used as a simple pass-through vehicle. Money comes in from one or more sources and is then immediately sent out in a single, consolidated payment. This pattern is highly suspicious and can be indicative of money laundering or attempts to obfuscate the trail of funds. The rule scores based on how few transactions it takes to mirror the outgoing amount—the fewer, the more suspicious.

Each rule is documented with its business logic, detection algorithm, configurable parameters, required KYC and core banking data inputs, cache requirements, regulatory references, and alert output specifications. and also db design for that specific rule the fields and the variables configurations.
