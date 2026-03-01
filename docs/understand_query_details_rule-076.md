# Full Breakdown of Rule-076

## What This Rule Does
This is a simple time-based velocity rule that measures the elapsed time (in milliseconds) between a **debtor's** two most recent **outgoing** payments. A very short time difference can be an indicator of automated activity, scripting, or a "fan-out" fraud scheme where a compromised account is being emptied quickly.

## SQL Query — Deep Dive
### Parameters Passed In
| Parameter | Value | Purpose |
|---|---|---|
| $1 | `debtorAccountId` | The ID of the debtor account whose outgoing payments are being analyzed. |
| $2 | `req.DataCache.creDtTm` | The timestamp of the current transaction, used as an upper time limit. |
| $3 | `tenantId` | For multi-tenant data isolation. |

### Query Logic
The rule uses a single, direct SQL query to find the timestamps of the last two outgoing payments.
```sql
SELECT
  CreDtTm::timestamptz AS "CreDtTm"
FROM
  transaction
WHERE
  TxTp = 'pacs.008.001.10'
  AND source = $1
  AND CreDtTm::timestamptz <= $2::timestamptz
  AND TenantId = $3
ORDER BY
  creDtTm::timestamptz DESC
LIMIT
  2;
```
**Purpose**: To retrieve the creation timestamps (`CreDtTm`) of the two most recent `pacs.008` (credit transfer) transactions where the specified `debtorAccountId` was the `source`.

**Result**: A list containing the timestamps of the debtor's two most recent outgoing payments.

---
## Post-Query Logic
1.  The code receives the two timestamps from the query.
2.  If only one or zero timestamps are returned, it exits due to insufficient history.
3.  It calculates the `timeDifferenceInMs` by subtracting the older timestamp from the newer one.
4.  This time difference is the final value passed to `determineOutcome`.

---
## Variable Importance Map
```
debtorAccountId      ──► WHO (The account sending the funds)
timeDifferenceInMs   ──► COMPUTED VALUE (Time elapsed between the last two outgoing payments)
```
A **low** `timeDifferenceInMs` indicates higher risk.

## Data Requirements

### Configurable Parameters
| Parameter | Type | Description |
|---|---|---|
| `bands` | `Array` | The array of time-based bands (in milliseconds) for scoring. |
| `exitConditions`| `Array` | Conditions for exiting the rule early. |

### Required KYC & Core Banking Data
| Field | Path | Description |
|---|---|---|
| `TenantId` | `req.transaction.TenantId` | The identifier for the tenant. |

### Cache Requirements
| Field | Path | Description |
|---|---|---|
| `dbtrAcctId`| `req.DataCache.dbtrAcctId` | The account ID of the debtor. |
| `creDtTm` | `req.DataCache.creDtTm` | The timestamp of the current transaction. |

## How to Implement This in Your Application
### Database Table: transaction
```sql
CREATE TABLE transaction (
  id          SERIAL PRIMARY KEY,
  source      VARCHAR(50)  NOT NULL, -- The debtor account
  TxTp        VARCHAR(30)  NOT NULL,
  CreDtTm     TIMESTAMPTZ  NOT NULL,
  TenantId    VARCHAR(50)  NOT NULL,
  INDEX idx_source_txtp_credtm_tenant (source, TxTp, CreDtTm, TenantId)
);
```
### Your Rule Config Structure
```json
{
  "config": {
    "exitConditions": [
      { "subRuleRef": ".x01", "reason": "Insufficient outgoing payment history" }
    ],
    "bands": [
      // Time in milliseconds
      { "subRuleRef": ".01", "lowerLimit": 0, "upperLimit": 10000 },    // Less than 10 seconds between payments
      { "subRuleRef": ".02", "lowerLimit": 10000, "upperLimit": 60000 } // 10 to 60 seconds between payments
    ]
  }
}
```

### Key Insight
This rule is a fundamental velocity check. While legitimate users can make multiple payments in a short time, extremely short intervals (seconds or less) are often indicative of machine-speed, automated processes. This rule provides a simple but effective way to flag accounts that are sending funds at a rate that is unlikely to be human-generated, a common characteristic of fraud and abuse.

Each rule is documented with its business logic, detection algorithm, configurable parameters, required KYC and core banking data inputs, cache requirements, regulatory references, and alert output specifications. and also db design for that specific rule the fields and the variables configurations.
