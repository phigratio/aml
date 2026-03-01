# Full Breakdown of Rule-901

## What This Rule Does
This rule counts the number of successful incoming transactions for a **debtor** account within a specific, configurable time window (`maxQueryRange`). It is functionally identical to Rule-017 and Rule-044, serving as a measure of recent transaction volume for the receiving party. The primary difference is a slightly different method of calculating the time window in the `WHERE` clause.

## SQL Query — Deep Dive
### Parameters Passed In
| Parameter | Value | Purpose |
|---|---|---|
| $1 | `debtorAccountId` | The ID of the debtor account receiving the transactions. |
| $2 | `currentPacs002TimeFrame`| The timestamp of the current transaction, which serves as the end of the time window. |
| $3 | `maxQueryRange` | A duration in milliseconds defining the lookback period. |
| $4 | `tenantId` | For multi-tenant data isolation. |

### Query Logic
The rule uses a single, direct `COUNT` query.
```sql
SELECT COUNT(*)::int AS length
FROM transaction tr
WHERE tr.destination = $1
AND tr."txtp" = 'pacs.002.001.12'
AND ($2::timestamptz - tr."credttm"::timestamptz) <= $3 * interval '1 millisecond'
AND tr.tenantId = $4;
```
**Purpose**: To count how many transaction events meet the following criteria:
1.  Were sent to the specified `debtorAccountId` (`tr.destination = $1`).
2.  Are payment status reports (`tr."txtp" = 'pacs.002.001.12'`).
3.  Occurred within the time window, calculated as `current_timestamp - historical_timestamp <= maxQueryRange`.

**Result**: A single integer value, `length`, representing the total count of matching transactions.

---

## ISO 20022 Message Types Used
This rule would typically check for `ACCC` (settled) status in its guard clauses, but the query itself only filters on the `pacs.002` message type.

| Code | Full Name | Role in Rule |
|---|---|---|
| `pacs.002.001.12` | FI To FI Payment Status Report | The transaction type being counted. |

---

## Variable Importance Map
```
debtorAccountId    ──► WHO (The account whose incoming activity is being measured)
maxQueryRange      ──► HOW (The duration of the lookback window)
length             ──► COMPUTED COUNT (The number of transactions in the window)
```

## Data Requirements

### Configurable Parameters
| Parameter | Type | Description |
|---|---|---|
| `maxQueryRange` | `number` | The lookback period in milliseconds. |
| `bands` | `Array` | The array of count-based bands for scoring. |

### Required KYC & Core Banking Data
| Field | Path | Description |
|---|---|---|
| `TxSts` | `req.transaction.FIToFIPmtSts.TxInfAndSts.TxSts` | The status of the current transaction. |
| `CreDtTm` | `req.transaction.FIToFIPmtSts.GrpHdr.CreDtTm` | The creation time of the current transaction. |
| `TenantId` | `req.transaction.TenantId` | The identifier for the tenant. |

### Cache Requirements
| Field | Path | Description |
|---|---|---|
| `dbtrAcctId`| `req.DataCache.dbtrAcctId` | The account ID of the debtor. |

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
This rule is a fundamental velocity check, focused on the volume of incoming payments to a single account over a recent period. A high volume can be a strong indicator that the account is being used as a "mule" or collection point for a fraudulent scheme. By tuning the `maxQueryRange` and the `bands`, institutions can target specific high-frequency scenarios that deviate from normal customer behavior.

Each rule is documented with its business logic, detection algorithm, configurable parameters, required KYC and core banking data inputs, cache requirements, regulatory references, and alert output specifications. and also db design for that specific rule the fields and the variables configurations.
