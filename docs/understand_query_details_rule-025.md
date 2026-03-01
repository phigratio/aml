# Full Breakdown of Rule-025

## What This Rule Does
This rule is the counterpart to Rule-024 and detects "transaction mirroring" from the **debtor's** perspective. It identifies if a recent incoming payment to the debtor was preceded by a series of smaller outgoing payments from that same debtor that sum up to the incoming amount (within a tolerance). This can indicate that the debtor account is being used to aggregate funds before sending them on, a pattern sometimes seen in money muling.

## SQL Query — Deep Dive
### Parameters Passed In
| Parameter | Value | Purpose |
|---|---|---|
| $1 | `debtorAccountId` | The ID of the debtor account to analyze. |
| $2 | `currentPacs002TimeFrame`| The upper time limit for the query window. |
| $3 | `maxQueryRange` | The lookback period in milliseconds to find the prior outgoing transactions. |
| $4 | `req.DataCache.creDtTm` | The timestamp of the target incoming transaction. |
| $5 | `tenantId` | For multi-tenant data isolation. |

### Query Logic
```sql
WITH all_success AS (
  SELECT DISTINCT EndToEndId
  FROM transaction
  WHERE source = $1 AND TxTp = 'pacs.002.001.12' AND TxSts = 'ACCC' AND TenantId = $5
    AND CreDtTm::timestamptz BETWEEN $2::timestamptz - ($3::bigint * interval '1 millisecond') AND $2::timestamptz
)
SELECT
  COALESCE(
    array_agg(t.Amt ORDER BY t.CreDtTm::timestamptz DESC),
    '{}'::numeric []
  ) AS "historicalAmounts"
FROM transaction t JOIN all_success s USING (EndToEndId)
WHERE t.TxTp = 'pacs.008.001.10' AND t.TenantId = $5 AND t.CreDtTm::timestamptz <= $4::timestamptz;
```
**Purpose**:
1.  The `all_success` CTE finds all successful outgoing transactions from the debtor within the `maxQueryRange`.
2.  The main query retrieves the amounts of these transactions and aggregates them into a single array, `historicalAmounts`.

**Result**: A single row containing an array of historical outgoing payment amounts from the debtor.

---
## Post-Query Logic
The `amountTracking` function takes the `targetAmount` (the current incoming payment) and the `historicalAmounts` (the prior outgoing payments). It iterates through the historical amounts, summing them up. If the running total ever closely matches the `targetAmount` (within the `tolerance`), it returns the number of transactions it took to reach that sum. This count is the final value passed to `determineOutcome`.

---
## Variable Importance Map
```
debtorAccountId    ──► WHO (The account being used to aggregate funds)
targetAmount       ──► WHAT (The incoming payment being mirrored)
historicalAmounts  ──► HOW (The prior outgoing payments that make up the mirror)
iterationValue     ──► COMPUTED COUNT (Number of prior txns needed to mirror the amount)
```

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
This rule detects when an account is used to collect funds that are then sent onward, mirroring an incoming payment. For example, a mule account might make several small outgoing payments which are then "reimbursed" by a single large incoming payment from a criminal organization. This rule identifies this by checking if the incoming payment amount can be constructed from a sum of recent outgoing payments.

Each rule is documented with its business logic, detection algorithm, configurable parameters, required KYC and core banking data inputs, cache requirements, regulatory references, and alert output specifications. and also db design for that specific rule the fields and the variables configurations.
