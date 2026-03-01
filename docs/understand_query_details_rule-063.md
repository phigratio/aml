# Full Breakdown of Rule-063

## What This Rule Does
This rule is the counterpart to Rule-054 and applies **Benford's Law** to the transaction amounts sent *from* a **creditor** account. Benford's Law is a statistical principle that describes the expected frequency of leading digits in many naturally occurring sets of numbers. This rule checks if the distribution of first digits in a creditor's outgoing payment history conforms to this natural pattern. A significant deviation can suggest that the payment amounts are being artificially constructed, for example, to avoid detection thresholds.

## SQL Query — Deep Dive
### Parameters Passed In
| Parameter | Value | Purpose |
|---|---|---|
| $1 | `creditorAccountId` | The ID of the **creditor** account whose outgoing payments are being analyzed. |
| $2 | `currentPacs002TimeFrame`| The timestamp of the current transaction, used as an upper time limit. |
| $3 | `tenantId` | For multi-tenant data isolation. |

### Query Logic
```sql
WITH all_success AS (
  SELECT DISTINCT EndToEndId
  FROM transaction
  WHERE source = $1 AND TxTp = 'pacs.002.001.12' AND TxSts = 'ACCC'
    AND CreDtTm::timestamptz <= $2::timestamptz AND TenantId = $3
)
SELECT t.Amt AS "Amt"
FROM transaction AS t JOIN all_success s USING (EndToEndId)
WHERE t.TxTp = 'pacs.008.001.10' AND t.TenantId = $3;
```
**Purpose**: The query retrieves a complete list of all historical transaction amounts (`Amt`) for successfully settled payments sent from the specified `creditorAccountId`.

**Result**: A list of all historical outgoing transaction amounts for the creditor.

---
## Post-Query Statistical Analysis
The core logic resides in the `calculateBenfordsLaw` function, which is identical to the one in Rule-054.

1.  **Observed Distribution**: It counts the frequency of the first digit (1-9) of each amount in the `historicalAmounts` list.
2.  **Expected Distribution**: It calculates the expected frequency for each digit based on Benford's Law (`P(d) = log10(1 + 1/d)`).
3.  **Chi-Square Test**: It performs a Chi-Square goodness-of-fit test to measure how much the `observed` distribution deviates from the `expected` one.
4.  **Final Value**: The function returns the `chiSquareValue`. A **high value** indicates a poor fit to Benford's Law and thus a higher risk. This value is passed to `determineOutcome`.

---
## Variable Importance Map
```
creditorAccountId  ──► WHO (The account whose outgoing payment amounts are being tested)
historicalAmounts  ──► WHAT (The set of numbers being analyzed)
chiSquareValue     ──► COMPUTED SCORE (How much the data deviates from Benford's Law)
```

## How to Implement This in Your Application
### Your Rule Config Structure
```json
{
  "config": {
    "parameters": {
      "minimumNumberOfTransactions": 50 // A sufficiently large data set is needed for this analysis
    },
    "exitConditions": [
      { "subRuleRef": ".x01", "reason": "Insufficient transaction history for Benford's analysis" }
    ],
    "bands": [
      // Chi-Square critical values for 8 degrees of freedom (9 digits - 1)
      { "subRuleRef": ".01", "lowerLimit": 15.51, "upperLimit": 20.09 }, // p-value < 0.05 (Suspicious deviation)
      { "subRuleRef": ".02", "lowerLimit": 20.09, "upperLimit": null }  // p-value < 0.01 (Highly suspicious deviation)
    ]
  }
}
```
### Key Insight
This rule is a powerful tool for detecting fraud that involves manipulating payment amounts. For example, a fraudster might be systematically generating payments designed to stay just under a specific reporting threshold (e.g., sending many payments of $9,900). This artificial pattern would cause the first-digit distribution to heavily favor '9' and violate Benford's Law. This rule automatically flags such unnatural datasets, providing a strong signal of potential structuring or other financial crime.

Each rule is documented with its business logic, detection algorithm, configurable parameters, required KYC and core banking data inputs, cache requirements, regulatory references, and alert output specifications. and also db design for that specific rule the fields and the variables configurations.
