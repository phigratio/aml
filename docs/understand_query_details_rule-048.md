# Full Breakdown of Rule-048

## What This Rule Does
This rule is a powerful, adaptive anomaly detection mechanism focused on the **monetary value** of incoming payments to a **debtor** account. It is the direct counterpart to Rule-020. It establishes a statistical baseline of "normal" transaction values for the debtor by calculating the historical average and standard deviation of past payment amounts. It then flags the current transaction if its amount is a significant statistical deviation from this established norm.

## SQL Query — Deep Dive
### Parameters Passed In
| Parameter | Value | Purpose |
|---|---|---|
| $1 | `debtorAccount` | The ID of the **debtor** account to be profiled. |
| $2 | `currentPacs002TimeFrame`| The timestamp of the current transaction, used as an upper time limit for the query. |
| $3 | `tenantId` | For multi-tenant data isolation. |

### Query Logic
The rule uses a `processQuery` function that contains the SQL.
```sql
WITH all_success AS (
  SELECT DISTINCT EndToEndId
  FROM transaction
  WHERE destination = $1 AND TxTp = 'pacs.002.001.12' AND TxSts = 'ACCC'
    AND CreDtTm::timestamptz <= $2::timestamptz AND TenantId = $3
)
SELECT
  t.EndToEndId AS "EndToEndId",
  t.CreDtTm AS "CreDtTm",
  CAST (t.Amt AS DOUBLE PRECISION) AS "Amount"
FROM transaction AS t JOIN all_success s USING (EndToEndId)
WHERE t.TxTp = 'pacs.008.001.10' AND t.TenantId = $3;
```
**Purpose**:
1.  The `all_success` CTE first identifies all `EndToEndId`s for successfully settled (`ACCC`) transactions received by the `debtorAccount`.
2.  The main query then joins back to the `transaction` table to pull the full details—most importantly the `Amount`—for each of those original `pacs.008` payments.

**Result**: A complete, ordered list of all historical transaction amounts received by the debtor.

---
## Post-Query Statistical Analysis
The core analytical logic is identical to Rule-020 and occurs after the data is fetched.

1.  **Isolate Current Transaction**: The code identifies the current transaction within the full historical list and separates its amount (`currentTransaction.Amount`) for evaluation.
2.  **Calculate Baseline**: It then uses all *other* historical transaction amounts to calculate the **average (`avg`)** and **standard deviation (`stdDev`)**. This establishes the account's unique financial "fingerprint" for incoming funds.
3.  **Handle Zero Variance**: The rule has specific exit conditions (`.x03`, `.x04`) to handle the case where `stdDev` is zero.
4.  **Dynamic Bands**: The rule's configured `bands` are **standard deviation multipliers**. The code dynamically recalculates the final thresholds for evaluation using the formula: `final_limit = (band_limit * stdDev) + avg`.
5.  **Final Evaluation**: The `determineOutcome` function is called, comparing the `currentTransaction.Amount` against these new, statistically-derived bands.

---
## Variable Importance Map
```
debtorAccount   ──► WHO (The account being profiled)
currentAmount   ──► WHAT (The transaction amount being scored)
avg             ──► COMPUTED BASELINE (The account's historical average received payment value)
stdDev          ──► COMPUTED DEVIATION (The account's historical received payment value volatility)
```

## How to Implement This in Your Application
### Your Rule Config Structure
The `lowerLimit` and `upperLimit` in the bands are **standard deviation multipliers**.
```json
{
  "config": {
    "exitConditions": [...],
    "bands": [
      { "subRuleRef": ".01", "lowerLimit": 2.5, "upperLimit": 4.0 }, // Amount is between (2.5*stdDev)+avg and (4.0*stdDev)+avg
      { "subRuleRef": ".02", "lowerLimit": 4.0, "upperLimit": null }  // Amount is >= (4.0*stdDev)+avg
    ]
  }
}
```
### Key Insight
This rule is highly effective at detecting payments that are "out of character" for the receiving account. A $15,000 payment to a corporate account might be normal, but a $15,000 payment to an individual's account that has never received more than $1,000 is a significant anomaly. By creating a unique statistical baseline for every account, this rule automatically flags such deviations, making it a powerful tool for detecting mule activity, initial stages of fraud, or payments related to illicit services.

Each rule is documented with its business logic, detection algorithm, configurable parameters, required KYC and core banking data inputs, cache requirements, regulatory references, and alert output specifications. and also db design for that specific rule the fields and the variables configurations.
