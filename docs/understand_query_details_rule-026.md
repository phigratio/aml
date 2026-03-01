# Full Breakdown of Rule-026

## What This Rule Does
This rule is an enhanced version of Rule-024, detecting "transaction mirroring" for a **creditor** account with an added wrinkle: **commission**. It identifies if a recent outgoing payment from the creditor was preceded by a series of smaller incoming payments that sum up to the outgoing amount *plus a commission fee*. This is a more realistic model for pass-through laundering, where the money mule takes a cut.

## SQL Query — Deep Dive
The SQL query is identical to Rule-024. It isolates a `targetAmount` (a recent outgoing payment) and a list of `historicalAmounts` (prior incoming payments).

### Parameters Passed In
| Parameter | Value | Purpose |
|---|---|---|
| $1 | `creditorAccountId` | The ID of the creditor account to analyze. |
| $2 | `req.DataCache.creDtTm` | The timestamp of the target outgoing transaction. |
| $3 | `maxQueryRange` | The lookback period in milliseconds. |
| $4 | `currentPacs002TimeFrame` | The timestamp of the current transaction being processed. |
| $5 | `tenantId` | For multi-tenant data isolation. |

---
## Post-Query Logic
The key difference lies in the `amountTracking` function.
```javascript
const amountTracking = (tolerance, commission, targetAmount, historicalAmounts) => {
    let amountTracker = 0;
    for (let i = 0; i < historicalAmounts.length; i += 1) {
        const amount = historicalAmounts[i];
        amountTracker += amount;
        const commissionAmount = targetAmount * commission;
        // Check if the sum of incoming payments matches the outgoing payment PLUS commission
        if (amountTracker > targetAmount + commissionAmount - (commissionAmount * tolerance) &&
            amountTracker < targetAmount + commissionAmount + (commissionAmount * tolerance)) {
            return i + 1;
        }
    }
    return -1;
};
```
It iterates through the `historicalAmounts`, summing them up. It checks if the running total matches the `targetAmount` plus a calculated `commissionAmount`, within a `tolerance`. If a match is found, it returns the number of transactions required to make the sum.

---
## Variable Importance Map
```
creditorAccountId  ──► WHO (The account being used as a pass-through)
commission         ──► HOW MUCH (The percentage cut the mule is taking)
targetAmount       ──► WHAT (The outgoing payment being mirrored)
historicalAmounts  ──► HOW (The prior incoming payments that make up the mirror)
iterationValue     ──► COMPUTED COUNT (Number of txns needed to mirror the amount + commission)
```

## Data Requirements

### Configurable Parameters
| Parameter | Type | Description |
|---|---|---|
| `tolerance` | `number` | The percentage (e.g., 0.02 for 2%) for fuzzy matching. |
| `commission` | `number` | The expected commission percentage (e.g., 0.10 for 10%). |
| `maxQueryRange`| `number` | The lookback period in milliseconds. |

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
      "tolerance": 0.02, // 2%
      "commission": 0.10 // 10% commission
    },
    "exitConditions": [...],
    "bands": [
      { "subRuleRef": ".01", "lowerLimit": 2, "upperLimit": 4 },
      { "subRuleRef": ".02", "lowerLimit": 4, "upperLimit": null }
    ]
  }
}
```
### Key Insight
This rule improves upon basic transaction mirroring by accounting for the commission a money mule might take. A fraudster might instruct a mule to expect a total of $1000 in incoming payments, send a payment of $900, and keep $100 as their fee. This rule is specifically designed to catch this `incoming_sum = outgoing_payment + commission` pattern, making it a more robust tool for detecting real-world money laundering techniques.

Each rule is documented with its business logic, detection algorithm, configurable parameters, required KYC and core banking data inputs, cache requirements, regulatory references, and alert output specifications. and also db design for that specific rule the fields and the variables configurations.
