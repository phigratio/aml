# Full Breakdown of Rule-054

## What This Rule Does
This is a powerful statistical rule that checks if the transaction amounts received by a **debtor** conform to **Benford's Law**. Benford's Law states that in many naturally occurring sets of numerical data, the leading digit is more likely to be small (1, 2, 3) than large (7, 8, 9). Data that has been artificially generated or manipulated often deviates from this pattern. This rule calculates how closely the first digits of a debtor's historical transaction amounts follow Benford's distribution, flagging significant deviations as suspicious.

## SQL Query — Deep Dive
### Parameters Passed In
| Parameter | Value | Purpose |
|---|---|---|
| $1 | `debtorAccountId` | The ID of the debtor account to analyze. |
| $2 | `currentPacs002TimeFrame`| The timestamp of the current transaction, used as an upper time limit. |
| $3 | `tenantId` | For multi-tenant data isolation. |

### Query Logic
```sql
WITH all_success AS (
  SELECT DISTINCT EndToEndId
  FROM transaction
  WHERE destination = $1 AND TxTp = 'pacs.002.001.12' AND TxSts = 'ACCC'
    AND CreDtTm::timestamptz <= $2::timestamptz AND TenantId = $3
)
SELECT t.Amt AS "Amt"
FROM transaction AS t JOIN all_success s USING (EndToEndId)
WHERE t.TxTp = 'pacs.008.001.10' AND t.TenantId = $3;
```
**Purpose**: The query retrieves a complete list of all historical transaction amounts (`Amt`) for successfully settled payments received by the specified `debtorAccountId`.

**Result**: A list of all historical incoming transaction amounts for the debtor.

---
## Post-Query Statistical Analysis
The core of this rule is the `calculateBenfordsLaw` function.

1.  **Observed Distribution**: The function iterates through the list of `historicalAmounts` and counts the frequency of the first digit of each amount (1 through 9).
2.  **Expected Distribution**: It calculates the expected count for each first digit based on Benford's Law (`Expected Count for digit 'd' = log10(1 + 1/d) * total_transactions`).
3.  **Chi-Square Test**: It performs a Chi-Square goodness-of-fit test. This statistical test measures how far apart the `observed` distribution is from the `expected` Benford's distribution.
4.  **Final Value**: The function returns the `chiSquareValue`. A low value indicates a good fit to Benford's Law (natural data), while a **high value** indicates a poor fit, suggesting the numbers may be artificial. This value is passed to `determineOutcome`.

---
## Variable Importance Map
```
debtorAccountId   ──► WHO (The account whose transaction amounts are being tested)
historicalAmounts ──► WHAT (The set of numbers being analyzed)
chiSquareValue    ──► COMPUTED SCORE (How much the data deviates from Benford's Law)
```
A **high** `chiSquareValue` indicates higher risk.

## How to Implement This in Your Application
### Your Rule Config Structure
```json
{
  "config": {
    "parameters": {
      "minimumNumberOfTransactions": 50 // Benford's Law requires a sufficiently large data set
    },
    "exitConditions": [
      { "subRuleRef": ".x01", "reason": "Insufficient transaction history for Benford's analysis" }
    ],
    "bands": [
      // Chi-Square critical values for 8 degrees of freedom (9 digits - 1)
      { "subRuleRef": ".01", "lowerLimit": 15.51, "upperLimit": 20.09 }, // p-value < 0.05 (Suspicious)
      { "subRuleRef": ".02", "lowerLimit": 20.09, "upperLimit": null }  // p-value < 0.01 (Highly Suspicious)
    ]
  }
}
```
### Key Insight
Benford's Law is a well-established method for fraud detection in financial and accounting data. Naturally occurring financial data (like invoice amounts, payment values) tends to follow this law. Data that is fabricated, such as amounts designed to stay just under a reporting threshold (e.g., $9,999), will violate this distribution. This rule is a powerful, automated tool to flag datasets that "don't look natural," which can be a strong signal of structured, fraudulent, or manipulated payment activity.

Each rule is documented with its business logic, detection algorithm, configurable parameters, required KYC and core banking data inputs, cache requirements, regulatory references, and alert output specifications. and also db design for that specific rule the fields and the variables configurations.
