# Full Breakdown of Rule-902

## What This Rule Does
This rule is the direct counterpart to Rule-901 and is functionally identical to Rule-016 and Rule-045. It counts the number of successful outgoing transactions from a **creditor** account within a specific, configurable time window (`maxQueryRange`). This serves as a fundamental measure of recent transaction volume for the sending party.

## SQL Query — Deep Dive
### Parameters Passed In
| Parameter | Value | Purpose |
|---|---|---|
| $1 | `creditorAccountId` | The ID of the **creditor** account sending the transactions. |
| $2 | `currentPacs002TimeFrame`| The timestamp of the current transaction, which serves as the end of the time window. |
| $3 | `maxQueryRange` | A duration in milliseconds defining the lookback period. |
| $4 | `tenantId` | For multi-tenant data isolation. |

### Query Logic
The rule uses a single, direct `COUNT` query. The key difference from Rule-901 is that it filters on `tr.source = $1`.
```sql
SELECT COUNT(*)::int AS length
FROM transaction tr
WHERE tr.source = $1
AND tr."txtp" = 'pacs.002.001.12'
AND ($2::timestamptz - tr."credttm"::timestamptz) <= $3 * interval '1 millisecond'
AND tr.tenantId = $4;
```
**Purpose**: To count how many transaction events meet the following criteria:
1.  Originated from the specified `creditorAccountId` (`tr.source = $1`).
2.  Are payment status reports (`tr."txtp" = 'pacs.002.001.12'`).
3.  Occurred within the time window, calculated as `current_timestamp - historical_timestamp <= maxQueryRange`. (Note: The query does not explicitly check for `TxSts = 'ACCC'`, but the rule's guard clauses do).

**Result**: A single integer value, `length`, representing the total count of matching transactions.

---

## ISO 20022 Message Types Used

| Code | Full Name | Role in Rule |
|---|---|---|
| `pacs.002.001.12` | FI To FI Payment Status Report | The transaction type being counted. |

---

## Variable Importance Map
```
creditorAccountId  ──► WHO (The account whose outgoing activity is being measured)
maxQueryRange      ──► HOW (The duration of the lookback window)
length             ──► COMPUTED COUNT (The number of transactions in the window)
```

## How to Implement This in Your Application
### Your Rule Config Structure
```json
{
  "config": {
    "parameters": {
      "maxQueryRange": 86400000   // 24 hours in milliseconds
    },
    "bands": [
      { "subRuleRef": ".01", "lowerLimit": 100, "upperLimit": 250 },
      { "subRuleRef": ".02", "lowerLimit": 250, "upperLimit": null }
    ]
  }
}
```
### Key Insight
This rule is a fundamental velocity check on the sending party. A high volume of outgoing payments from a single account in a short period is a primary indicator of potential "fan-out" activity, where a compromised or fraudulent account is used to rapidly disperse funds to multiple other accounts. By tuning the `maxQueryRange` and the `bands`, institutions can create alerts for specific high-frequency scenarios that warrant investigation.

Each rule is documented with its business logic, detection algorithm, configurable parameters, required KYC and core banking data inputs, cache requirements, regulatory references, and alert output specifications. and also db design for that specific rule the fields and the variables configurations.
