# Full Breakdown of Rule-011

## What This Rule Does
This rule is the direct counterpart to Rule-010, focused on detecting anomalies in the transaction **frequency** of a **creditor** account. It establishes a statistical baseline of "normal" outgoing payment frequency for the account by calculating a historical average and standard deviation. It then flags the current transaction if the number of payments in the most recent time period represents a significant deviation from that established baseline.

## SQL Query — Deep Dive
### Parameters Passed In
| Parameter | Value | Purpose |
|---|---|---|
| $1 | `creditorAccount` | The ID of the **creditor** account to analyze. |
| $2 | `currentPacs002TimeFrame`| The timestamp of the current transaction, serving as the upper time limit for the history. |
| $3 | `tenantId` | For multi-tenant data isolation. |

### Query Logic
The rule uses a single query to fetch the necessary historical data. The key difference from Rule-010 is `WHERE source = $1`.
```sql
SELECT
  EndToEndId AS "EndToEndId",
  CreDtTm AS "CreDtTm"
FROM
  transaction
WHERE
  source = $1
  AND TxTp = 'pacs.002.001.12'
  AND TxSts = 'ACCC'
  AND CreDtTm::timestamptz <= $2::timestamptz
  AND TenantId = $3
ORDER BY
  CreDtTm::timestamptz DESC;
```
**Purpose**: To retrieve the `EndToEndId` and creation timestamp (`CreDtTm`) of **all** successfully settled (`ACCC`) transactions where the specified `creditorAccount` was the **source**. This provides a complete history of the creditor's outgoing payment activity.

**Result**: A complete, ordered list of historical transaction timestamps for the creditor.

---
## Post-Query Statistical Analysis
The core analytical logic is identical to Rule-010 and occurs after the query.

1.  **Histogram Creation**: The list of all transaction timestamps is grouped into time "buckets" based on the configured `evaluationIntervalTime`, creating a histogram of transaction frequencies.
2.  **Isolate Current Period**: The transaction count from the most recent time interval (`currentInterval`) is separated for analysis.
3.  **Calculate Baseline**: The code calculates the historical **average (`avg`)** and **standard deviation (`stdDev`)** of transaction frequency from the histogram, establishing the account's normal behavior.
4.  **Dynamic Bands**: The rule's `bands` (which are standard deviation multipliers) are used to dynamically calculate the final thresholds for evaluation using the formula: `final_limit = (band_limit * stdDev) + avg`.
5.  **Final Evaluation**: The `determineOutcome` function compares the `currentInterval` count against these dynamic, statistically-derived bands.

---

## ISO 20022 Message Types Used

| Code | Full Name | Role in Rule |
|---|---|---|
| `pacs.002.001.12`| FI To FI Payment Status Report | Used to get the timestamps of all successful transactions for the frequency analysis. |
| `ACCC` | AcceptedCreditSettlementCompleted | The status confirming a successful transaction. |

---

## Variable Importance Map
```
evaluationIntervalTime  ──► HOW (The size of the time buckets for frequency analysis)
currentInterval         ──► WHAT (The creditor's transaction count in the most recent period)
avg                     ──► COMPUTED BASELINE (The creditor's historical average frequency)
stdDev                  ──► COMPUTED DEVIATION (The creditor's historical volatility)
```

## How to Implement This in Your Application
The implementation details are identical to Rule-010, with the understanding that the analysis is applied to the creditor.

### Database Table: transaction
```sql
CREATE TABLE transaction (
  id          SERIAL PRIMARY KEY,
  EndToEndId  VARCHAR(35)  NOT NULL,
  source      VARCHAR(50)  NOT NULL, -- The creditor account
  TxTp        VARCHAR(30)  NOT NULL,
  TxSts       VARCHAR(10),
  CreDtTm     TIMESTAMPTZ  NOT NULL,
  TenantId    VARCHAR(50)  NOT NULL,

  INDEX idx_source_txtp_credtm_tenant (source, TxTp, CreDtTm, TenantId)
);
```
### Your Rule Config Structure
The `lowerLimit` and `upperLimit` in the bands are **standard deviation multipliers**.
```json
{
  "config": {
    "parameters": {
      "evaluationIntervalTime": 3600000 // 1 hour in milliseconds
    },
    "exitConditions": [...],
    "bands": [
      { "subRuleRef": ".01", "lowerLimit": 1.5, "upperLimit": 2.5 }, // Score if count is between (1.5*stdDev)+avg and (2.5*stdDev)+avg
      { "subRuleRef": ".02", "lowerLimit": 2.5, "upperLimit": 4.0 }, // Score if count is between (2.5*stdDev)+avg and (4.0*stdDev)+avg
      { "subRuleRef": ".03", "lowerLimit": 4.0, "upperLimit": null }  // Score if count is >= (4.0*stdDev)+avg
    ]
  }
}
```
### Data Flow Summary
```
Incoming pacs.002 transaction
        │
        ▼
Query DB ──► Get timestamps of ALL successful txns for the CREDITOR
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
                         Compare the CREDITOR's txn count from the 'currentInterval' against the dynamic bands
                                │
                                ▼
                         Return risk subRuleRef (.01/.02/.03)
```
### Key Insight
This rule is the other half of the frequency analysis pair and is critical for detecting "fan-out" or "cash-out" fraud. When a fraudster gains control of an account, they often try to empty it by sending many payments to various mule accounts as quickly as possible. This rule's adaptive, baseline-driven approach is extremely effective at catching this sudden, statistically significant change in outgoing payment behavior, even if the account was previously very active.

Each rule is documented with its business logic, detection algorithm, configurable parameters, required KYC and core banking data inputs, cache requirements, regulatory references, and alert output specifications. and also db design for that specific rule the fields and the variables configurations.
