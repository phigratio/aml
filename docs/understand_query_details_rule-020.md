# Full Breakdown of Rule-020

## What This Rule Does
This rule is a powerful, adaptive anomaly detection mechanism that focuses on the **monetary value** of outgoing payments from a **creditor** account. It establishes a statistical baseline of "normal" transaction amounts for that specific account by calculating the historical average and standard deviation. It then flags the current transaction if its amount is a significant statistical deviation from this established norm.

## SQL Query — Deep Dive
### Parameters Passed In
| Parameter | Value | Purpose |
|---|---|---|
| $1 | `cdtrAcctId` | The ID of the **creditor** account to be profiled. |
| $2 | `currentPacs002TimeFrame`| The timestamp of the current transaction, used as an upper time limit for the query. |
| $3 | `tenantId` | For multi-tenant data isolation. |

### Query Logic
The rule uses a single query with a CTE to fetch the complete, relevant transaction history.
```sql
WITH all_success AS (
  SELECT
    DISTINCT EndToEndId
  FROM
    transaction
  WHERE
    source = $1
    AND TxTp = 'pacs.002.001.12'
    AND TxSts = 'ACCC'
    AND CreDtTm::timestamptz <= $2::timestamptz
    AND TenantId = $3
)
SELECT
  t.EndToEndId as "EndToEndId",
  t.CreDtTm as "CreDtTm",
  t.Amt as "Amount"
FROM
  transaction AS t
  JOIN all_success s USING (EndToEndId)
WHERE
  t.TxTp = 'pacs.008.001.10'
  AND t.TenantId = $3
ORDER BY
  t.CreDtTm::timestamptz DESC;
```
**Purpose**:
1.  The `all_success` CTE first identifies all `EndToEndId`s for successfully settled (`ACCC`) transactions originating from the `cdtrAcctId`.
2.  The main query then joins back to the `transaction` table to pull the full details—most importantly the `Amount`—for each of those original `pacs.008` payments.

**Result**: A complete, ordered list of all historical transaction amounts for the creditor.

---
## Post-Query Statistical Analysis
The core of this rule's logic resides in the application code after the data is fetched.

1.  **Isolate Current Transaction**: The code identifies the current transaction within the full historical list and separates its amount (`currentAmount`) for evaluation.
2.  **Calculate Baseline**: It then uses all *other* historical transaction amounts to calculate the **average (`avg`)** and **standard deviation (`stdDev`)**. This establishes the account's unique financial "fingerprint."
3.  **Handle Zero Variance**: The rule has specific exit conditions (`.x03`, `.x04`) to handle the case where `stdDev` is zero (i.e., all historical transactions were for the exact same amount).
4.  **Dynamic Bands**: The rule's configured `bands` are **standard deviation multipliers**. The code dynamically recalculates the final thresholds for evaluation using the formula: `final_limit = (band_limit * stdDev) + avg`.
5.  **Final Evaluation**: The `determineOutcome` function is called, comparing the `currentAmount` against these new, statistically-derived bands.

---

## ISO 20022 Message Types Used

| Code | Full Name | Role in Rule |
|---|---|---|
| `pacs.008.001.10`| FI To FI Customer Credit Transfer | The source of the transaction `Amount` for statistical analysis. |
| `pacs.002.001.12`| FI To FI Payment Status Report | Used to filter for only successfully completed transactions. |
| `ACCC` | AcceptedCreditSettlementCompleted | The specific status code confirming a successful transaction. |

---

## Variable Importance Map
```
cdtrAcctId      ──► WHO (The account being profiled)
currentAmount   ──► WHAT (The transaction amount being scored)
avg             ──► COMPUTED BASELINE (The account's historical average payment value)
stdDev          ──► COMPUTED DEVIATION (The account's historical payment value volatility)
```

## How to Implement This in Your Application
### Database Table: transaction
```sql
CREATE TABLE transaction (
  id          SERIAL PRIMARY KEY,
  EndToEndId  VARCHAR(35)  NOT NULL,
  source      VARCHAR(50)  NOT NULL, -- The creditor account
  TxTp        VARCHAR(30)  NOT NULL,
  TxSts       VARCHAR(10),
  Amt         NUMERIC(18, 2) NOT NULL,
  CreDtTm     TIMESTAMPTZ  NOT NULL,
  TenantId    VARCHAR(50)  NOT NULL,

  INDEX idx_source_txtp_tenant (source, TxTp, TenantId)
);
```
### Your Rule Config Structure
The `lowerLimit` and `upperLimit` in the bands are **standard deviation multipliers**.
```json
{
  "config": {
    "exitConditions": [...],
    "bands": [
      { "subRuleRef": ".01", "lowerLimit": 2.0, "upperLimit": 3.0 },  // Score if amount is between (2*stdDev)+avg and (3*stdDev)+avg
      { "subRuleRef": ".02", "lowerLimit": 3.0, "upperLimit": 5.0 },  // Score if amount is between (3*stdDev)+avg and (5*stdDev)+avg
      { "subRuleRef": ".03", "lowerLimit": 5.0, "upperLimit": null }   // Score if amount is >= (5*stdDev)+avg
    ]
  }
}
```
### Data Flow Summary
```
Incoming pacs.002 transaction
        │
        ▼
Query DB ──► Get amounts of ALL successful historical txns for the CREDITOR
        │
        └── In code, separate the current transaction amount
                                │
                                ▼
                         Calculate historical 'avg' and 'stdDev' of amounts
                                │
                                ▼
                         Check for zero-variance exit conditions (.x03, .x04)
                                │
                                ▼
                         Dynamically calculate final band thresholds using avg and stdDev
                                │
                                ▼
                         Compare the 'currentAmount' against the dynamic bands
                                │
                                ▼
                         Return risk subRuleRef (.01/.02/.03)
```
### Key Insight
This rule is a prime example of adaptive, behavior-based anomaly detection. It moves beyond simple "high value" alerts by learning what is "normal" for each specific account. A $5,000 transaction might be completely normal for a large corporate payroll account but extremely unusual for a student's account. By using standard deviation, this rule automatically flags transactions that are monetarily "out of character," making it highly effective at detecting fraud on compromised accounts.

Each rule is documented with its business logic, detection algorithm, configurable parameters, required KYC and core banking data inputs, cache requirements, regulatory references, and alert output specifications. and also db design for that specific rule the fields and the variables configurations.
