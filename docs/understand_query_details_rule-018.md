# Full Breakdown of Rule-018

## What This Rule Does
This rule detects a sudden, significant escalation in the value of payments being made to a debtor. It works by finding the single highest transaction amount in the debtor's recent history and then calculates the ratio of the current transaction's amount to that historical maximum. A high ratio (e.g., the current payment is 10x larger than any previous payment) is a strong indicator of anomalous and potentially fraudulent activity.

## SQL Query — Deep Dive
### Parameters Passed In
| Parameter | Value | Purpose |
|---|---|---|
| $1 | `debtorAccount` | The ID of the debtor account receiving the funds. |
| $2 | `currentPacs002TimeFrame`| The timestamp of the current transaction, marking the end of the query window. |
| $3 | `maxQueryRange` | A duration in milliseconds defining the lookback period for the historical maximum. |
| $4 | `endToEndId` | The End-to-End ID of the current transaction, to ensure it is excluded from the historical search. |
| $5 | `tenantId` | For multi-tenant data isolation. |

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
  AND CreDtTm::timestamptz >= $2::timestamptz - ($3::bigint * interval '1 millisecond')
  AND EndToEndId <> $4
  AND TenantId = $5
```
**Purpose**: To create a set of `EndToEndId`s for all successfully settled (`ACCC`) incoming payments to the `debtorAccount` that occurred within the `maxQueryRange`, excluding the current transaction.

### Final SELECT
```sql
SELECT
  MAX(t.Amt) AS "highestAmount"
FROM
  transaction t
  JOIN all_success s USING (EndToEndId)
WHERE
  t.TxTp = 'pacs.008.001.10'
  AND t.TenantId = $5;
```
**Purpose**: The main query uses the set of successful `EndToEndId`s to look up the original `pacs.008` transactions and calculates the single `MAX` amount from that entire historical set.

**Result**: A single value, `highestAmount`, representing the largest single payment the debtor has received in the defined lookback period.

---
## Post-Query Logic
1.  The `highestAmount` is retrieved from the query.
2.  If the amount is `null` (no history), the rule exits.
3.  A `ratio` is calculated: `current transaction amount / highestAmount`.
4.  This `ratio` is the final value passed to `determineOutcome` to be scored against the bands.

---

## ISO 20022 Message Types Used

| Code | Full Name | Role in Rule |
|---|---|---|
| `pacs.008.001.10`| FI To FI Customer Credit Transfer | The source of the transaction amounts (`Amt`) for comparison. |
| `pacs.002.001.12`| FI To FI Payment Status Report | Used to identify the set of successfully completed historical transactions. |
| `ACCC` | AcceptedCreditSettlementCompleted | The status code confirming a successful transaction. |

---

## Variable Importance Map
```
debtorAccount    ──► WHO (The account being profiled)
maxQueryRange    ──► WHEN (The time window for the historical max)
instdAmt.amt     ──► WHAT (The amount of the current transaction)
highestAmount    ──► COMPUTED BASELINE (The historical max value)
ratio            ──► COMPUTED SCORE (Current Amount / Historical Max)
```
A high `ratio` signifies a high risk.

## How to Implement This in Your Application
### Database Table: transaction
```sql
CREATE TABLE transaction (
  id          SERIAL PRIMARY KEY,
  EndToEndId  VARCHAR(35)  NOT NULL,
  destination VARCHAR(50)  NOT NULL, -- The debtor account
  TxTp        VARCHAR(30)  NOT NULL,
  TxSts       VARCHAR(10),
  Amt         NUMERIC(18, 2) NOT NULL,
  CreDtTm     TIMESTAMPTZ  NOT NULL,
  TenantId    VARCHAR(50)  NOT NULL,
  INDEX idx_dest_txtp_credtm_tenant (destination, TxTp, CreDtTm, TenantId)
);
```
### Your Rule Config Structure
The `lowerLimit` and `upperLimit` in the bands are ratio multipliers.
```json
{
  "config": {
    "parameters": {
      "maxQueryRange": 2592000000 // 30 days in milliseconds
    },
    "exitConditions": [
      { "subRuleRef": ".x00", "reason": "Transaction was not successful" },
      { "subRuleRef": ".x01", "reason": "Insufficient transaction history for analysis" }
    ],
    "bands": [
      { "subRuleRef": ".01", "lowerLimit": 2, "upperLimit": 5 },   // Current txn is 2x-5x larger than historical max
      { "subRuleRef": ".02", "lowerLimit": 5, "upperLimit": 10 },  // Current txn is 5x-10x larger
      { "subRuleRef": ".03", "lowerLimit": 10, "upperLimit": null } // Current txn is 10x+ larger
    ]
  }
}
```
### Your determineOutcome Function
```javascript
function determineOutcome(ratio, ruleConfig, ruleResult) {
  const band = ruleConfig.config.bands.find(b =>
    ratio >= b.lowerLimit &&
    (b.upperLimit === null || ratio < b.upperLimit)
  );

  if (!band) {
    return { ...ruleResult, subRuleRef: '.00', reason: 'Transaction value is within normal historical limits.' };
  }

  return {
    ...ruleResult,
    subRuleRef: band.subRuleRef,
    reason: `Current transaction amount is ${ratio.toFixed(1)}x larger than the historical maximum.`
  };
}
```

### Data Flow Summary
```
Incoming pacs.002 transaction
        │
        ▼
Extract debtorAccount, current amount, endToEndId
        │
        ▼
Query DB ──► Get the single MAX(Amt) from the debtor's history
        │     within 'maxQueryRange', excluding the current txn.
        │
        ├── No history ──► Exit .x01
        │
        └── Has historical max ──► In code, calculate ratio:
                                    CurrentAmt / HistoricalMax
                                          │
                                          ▼
                                   Match the ratio against bands
                                          │
                                          ▼
                                   Return risk subRuleRef (.01/.02/.03)
```
### Key Insight
This rule is a classic and effective form of anomaly detection. It excels at catching transactions that are "out of character" for an account's established behavior. Fraudsters often make small test payments before attempting a large cash-out from a compromised account; this rule is specifically designed to flag that large, anomalous transaction by comparing it to the established historical pattern.

Each rule is documented with its business logic, detection algorithm, configurable parameters, required KYC and core banking data inputs, cache requirements, regulatory references, and alert output specifications. and also db design for that specific rule the fields and the variables configurations.
