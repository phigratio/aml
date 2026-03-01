# Full Breakdown of Rule-010

## What This Rule Does
This is a sophisticated anomaly detection rule that identifies unusual spikes in the **frequency** of transactions for a debtor account. Instead of using fixed thresholds, it establishes a statistical baseline of "normal" behavior for the account by calculating the historical average and standard deviation of transaction counts over time. It then evaluates if the number of transactions in the most recent time period is a significant deviation from this baseline.

## SQL Query — Deep Dive
### Parameters Passed In
| Parameter | Value | Purpose |
|---|---|---|
| $1 | `debtorAccountId` | The ID of the debtor account to analyze. |
| $2 | `currentPacs002TimeFrame`| The timestamp of the current transaction, serving as the upper time limit for the history. |
| $3 | `tenantId` | For multi-tenant data isolation. |

### Query Logic
The rule uses a single query to fetch the necessary historical data.
```sql
SELECT
  EndToEndId AS "EndToEndId",
  CreDtTm AS "CreDtTm"
FROM
  transaction
WHERE
  destination = $1
  AND TxTp = 'pacs.002.001.12'
  AND TxSts = 'ACCC'
  AND CreDtTm::timestamptz <= $2::timestamptz
  AND TenantId = $3
ORDER BY
  CreDtTm::timestamptz DESC;
```
**Purpose**: To retrieve the `EndToEndId` and creation timestamp (`CreDtTm`) of **all** successfully settled (`ACCC`) transactions ever received by the specified `debtorAccountId`. The entire history is then processed in the application layer.

**Result**: A complete, ordered list of historical transaction timestamps for the debtor.

---
## Post-Query Statistical Analysis
The real logic of this rule happens in the application code after the query.

1.  **Histogram Creation**: The list of all transaction timestamps is passed to a `createHistogram` function along with a configured `evaluationIntervalTime` (e.g., 1 day). This function groups the transactions into time "buckets" and counts how many occurred in each, creating a histogram of frequencies (e.g., `[5, 8, 6, 7, 12]`).
2.  **Isolate Current Period**: The count for the most recent time interval is separated from the historical data. This is the `currentInterval` value to be scored.
3.  **Calculate Baseline**: The code calculates the historical **average (`avg`)** and **standard deviation (`stdDev`)** from the remaining histogram buckets. This establishes the account's normal transaction frequency pattern.
4.  **Dynamic Bands**: The rule's configured `bands` are not absolute values; they are **standard deviation multipliers**. The code dynamically recalculates the final thresholds for evaluation using the formula: `final_limit = (band_limit * stdDev) + avg`.
5.  **Final Evaluation**: The `determineOutcome` function is called, comparing the `currentInterval` count against these newly calculated, dynamic bands.

---

## ISO 20022 Message Types Used

| Code | Full Name | Role in Rule |
|---|---|---|
| `pacs.002.001.12`| FI To FI Payment Status Report | Used to get the timestamps of all successful transactions for the frequency analysis. |
| `ACCC` | AcceptedCreditSettlementCompleted | The status confirming a successful transaction. |

---

## Variable Importance Map
```
evaluationIntervalTime  ──► HOW (The size of the time buckets for frequency analysis, e.g., 1 hour, 1 day)
currentInterval         ──► WHAT (The transaction count in the most recent period, the value being scored)
avg                     ──► COMPUTED BASELINE (The account's historical average frequency)
stdDev                  ──► COMPUTED DEVIATION (The account's historical volatility)
```

## Data Requirements

### Configurable Parameters
| Parameter | Type | Description |
|---|---|---|
| `evaluationIntervalTime` | `number` | The size of the time buckets (in ms) for creating the frequency histogram. |
| `bands` | `Array` | Array of standard deviation multipliers for scoring. |

### Required KYC & Core Banking Data
| Field | Path | Description |
|---|---|---|
| `TxSts` | `req.transaction.FIToFIPmtSts.TxInfAndSts.TxSts` | The status of the current transaction. |
| `OrgnlEndToEndId` | `req.transaction.FIToFIPmtSts.TxInfAndSts.OrgnlEndToEndId` | The unique ID of the current transaction. |
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
  destination VARCHAR(50)  NOT NULL, -- The debtor account
  TxTp        VARCHAR(30)  NOT NULL,
  TxSts       VARCHAR(10),
  CreDtTm     TIMESTAMPTZ  NOT NULL,
  TenantId    VARCHAR(50)  NOT NULL,

  INDEX idx_dest_txtp_credtm_tenant (destination, TxTp, CreDtTm, TenantId)
);
```
### Your Rule Config Structure
The `lowerLimit` and `upperLimit` in the bands are **standard deviation multipliers**.
```json
{
  "config": {
    "parameters": {
      "evaluationIntervalTime": 86400000 // 1 day in milliseconds
    },
    "exitConditions": [
        { "subRuleRef": ".x01", "reason": "Insufficient transaction history" },
        { "subRuleRef": ".x03", "reason": "No historical variance; current activity is an increase" },
        { "subRuleRef": ".x04", "reason": "No historical variance; current activity is not an increase" }
    ],
    "bands": [
      { "subRuleRef": ".01", "lowerLimit": 1.0, "upperLimit": 2.0 },  // Score if count is between (1*stdDev)+avg and (2*stdDev)+avg
      { "subRuleRef": ".02", "lowerLimit": 2.0, "upperLimit": 3.0 },  // Score if count is between (2*stdDev)+avg and (3*stdDev)+avg
      { "subRuleRef": ".03", "lowerLimit": 3.0, "upperLimit": null }  // Score if count is >= (3*stdDev)+avg
    ]
  }
}
```
### Data Flow Summary
```
Incoming pacs.002 transaction
        │
        ▼
Query DB ──► Get timestamps of ALL successful txns for the debtor
        │
        └── In code, create a histogram of txn counts per 'evaluationIntervalTime'
                                │
                                ▼
                         Calculate historical 'avg' and 'stdDev' (excluding current period)
                                │
                                ▼
                         Check for zero-variance exit conditions (.x03, .x04)
                                │
                                ▼
                         Dynamically calculate final band thresholds using avg and stdDev
                                │
                                ▼
                         Compare the txn count from the 'currentInterval' against the dynamic bands
                                │
                                ▼
                         Return risk subRuleRef (.01/.02/.03)
```
### Key Insight
This rule represents a powerful, adaptive approach to fraud detection. By establishing a statistical "normal" for each account, it can flag significant changes in behavior much more effectively than rules with static, one-size-fits-all thresholds. A sudden spike in transaction frequency, even if the total number is not excessively high, will be caught because it deviates significantly from the account's own established history. This makes it excellent for detecting the start of bust-out schemes, money muling, or other rapid fraudulent activities.

Each rule is documented with its business logic, detection algorithm, configurable parameters, required KYC and core banking data inputs, cache requirements, regulatory references, and alert output specifications. and also db design for that specific rule the fields and the variables configurations.
