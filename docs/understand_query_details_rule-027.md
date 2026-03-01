# Full Breakdown of Rule-027

## What This Rule Does
This rule is an enhanced version of Rule-025, detecting "transaction mirroring" for a **debtor** account with an added **commission** factor. It identifies if a recent incoming payment to the debtor was preceded by a series of smaller outgoing payments from that same account that sum up to the incoming amount *plus a commission*. This models a scenario where a mule is consolidating funds and sending them onward, keeping a fee for their service.

## SQL Query — Deep Dive
The SQL query is identical to Rule-025. It retrieves an array of `historicalAmounts` representing prior outgoing payments from the debtor.

### Parameters Passed In
| Parameter | Value | Purpose |
|---|---|---|
| $1 | `debtorAccountId` | The ID of the debtor account to analyze. |
| $2 | `currentPacs002TimeFrame`| The upper time limit for the query window. |
| $3 | `maxQueryRange` | The lookback period in milliseconds. |
| $4 | `req.DataCache.creDtTm` | The timestamp of the target incoming transaction. |
| $5 | `tenantId` | For multi-tenant data isolation. |

---
## Post-Query Logic
The key difference from Rule-025 lies in the `amountTracking` function, which now incorporates a `commission`.
```javascript
const amountTracking = (tolerance, commission, targetAmount, historicalAmounts) => {
    let amountTracker = 0;
    for (let i = 0; i < historicalAmounts.length; i += 1) {
        const amount = historicalAmounts[i];
        amountTracker += amount;
        const commissionAmount = targetAmount * commission;
        // Check if the sum of prior outgoing payments matches the incoming payment PLUS commission
        if (amountTracker > targetAmount + commissionAmount - (commissionAmount * tolerance) &&
            amountTracker < targetAmount + commissionAmount + (commissionAmount * tolerance)) {
            return i + 1;
        }
    }
    return -1;
};
```
It iterates through the `historicalAmounts` (prior outgoing payments), summing them up. It checks if the running total matches the `targetAmount` (the new incoming payment) plus a calculated `commissionAmount`.

---
## Variable Importance Map
```
debtorAccountId    ──► WHO (The account being used to aggregate funds)
commission         ──► HOW MUCH (The percentage fee the mule is taking)
targetAmount       ──► WHAT (The incoming payment being mirrored)
historicalAmounts  ──► HOW (The prior outgoing payments that make up the mirror)
iterationValue     ──► COMPUTED COUNT (Number of prior txns needed to mirror the amount + commission)
```

## How to Implement This in Your Application
### Your Rule Config Structure
```json
{
  "config": {
    "parameters": {
      "maxQueryRange": 604800000, // 7 days
      "tolerance": 0.02, // 2%
      "commission": 0.05 // 5% commission
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
This rule models a common money mule pattern. The mule receives a large payment (`targetAmount`), makes a series of smaller outgoing payments to consolidate or transfer the funds, and their "fee" is the amount left over in the account. This rule detects this by checking if the incoming amount can be explained by the sum of recent outgoing payments plus a commission. It's a more realistic and effective way to detect this type of pass-through fraud than a simple 1:1 amount match.

Each rule is documented with its business logic, detection algorithm, configurable parameters, required KYC and core banking data inputs, cache requirements, regulatory references, and alert output specifications. and also db design for that specific rule the fields and the variables configurations.
