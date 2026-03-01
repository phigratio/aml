# Full Breakdown of Rule-091

## What This Rule Does
This is the most basic type of rule possible: a direct thresholding rule. It performs no complex calculations or database queries. It simply takes the instructed amount (`instdAmt`) of the current transaction directly from the data cache and passes it to the `determineOutcome` function to be evaluated against configured monetary bands.

## Rule Logic
The entire logic of the rule is contained in a single line:
```javascript
async function handleTransaction(req, determineOutcome, ruleRes, ...) {
    // ... guard statements ...
    const amountRes = req.DataCache.instdAmt.amt;
    return determineOutcome(amountRes, ruleConfig, ruleRes);
}
```
**Purpose**: To evaluate the raw amount of the current transaction against a set of simple, static thresholds.

---
## SQL Query
This rule performs **no database queries**.

---
## Variable Importance Map
```
instdAmt.amt   ──► WHAT (The raw amount of the transaction being scored)
```

## How to Implement This in Your Application
### Your Rule Config Structure
The configuration for this rule consists of simple monetary bands.
```json
{
  "config": {
    "bands": [
      { "subRuleRef": ".01", "lowerLimit": 10000, "upperLimit": 20000 }, // e.g., flag amounts between 10k and 20k
      { "subRuleRef": ".02", "lowerLimit": 20000, "upperLimit": null }  // e.g., flag amounts over 20k
    ]
  }
}
```
### Your determineOutcome Function
```javascript
function determineOutcome(amount, ruleConfig, ruleResult) {
  const band = ruleConfig.config.bands.find(b =>
    amount >= b.lowerLimit &&
    (b.upperLimit === null || amount < b.upperLimit)
  );

  if (!band) {
    // Amount is below the minimum threshold
    return { ...ruleResult, subRuleRef: '.00', reason: 'Amount is below minimum risk threshold.' };
  }

  return {
    ...ruleResult,
    subRuleRef: band.subRuleRef,
    reason: `Transaction amount ${amount} falls within high-value band ${band.subRuleRef}.`
  };
}
```

### Data Flow Summary
```
Incoming pacs.002 transaction
        │
        ▼
Extract instructedAmount from Data Cache
        │
        ▼
 Match amount against static bands
        │
        ▼
 Return risk subRuleRef (.01/.02)
```
### Key Insight
This rule represents the simplest form of transaction monitoring: high-value reporting. While not as sophisticated as behavioral or statistical rules, it is a fundamental and often legally required component of any AML system. It provides a non-negotiable backstop to ensure that any transaction exceeding a certain value is flagged for review, regardless of the parties' history or other contextual factors.

Each rule is documented with its business logic, detection algorithm, configurable parameters, required KYC and core banking data inputs, cache requirements, regulatory references, and alert output specifications. and also db design for that specific rule the fields and the variables configurations.
