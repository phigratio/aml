# Full Breakdown of Rule-008

## What This Rule Does
This rule is designed to detect a "bust-out" or "cash-out" scenario. It analyzes the recent transaction history of a debtor account to see if the last several payments all originated from the same creditor. A high number of sequential, successful payments from a single source to a single destination can be a strong indicator of fraudulent activity, such as draining a compromised account.

## SQL Query — Deep Dive
### Parameters Passed In
| Parameter | Value | Purpose |
|---|---|---|
| $1 | `debtorAccountId` | The ID of the debtor account that is receiving the funds. |
| $2 | `currentPacs002TimeFrame`| The timestamp of the current transaction, used as an upper time limit. |
| $3 | `tenantId` | For multi-tenant data isolation. |
| $4 | `maxQueryLimit` | (Optional) Limits how many of the most recent transactions are analyzed. |

### Query Logic
The rule builds a query dynamically and then executes it.

```sql
SELECT
  t.source
FROM
  transaction AS t
WHERE
  t.destination = $1
  AND t.TxTp = 'pacs.002.001.12'
  AND t.TxSts = 'ACCC'
  AND t.CreDtTm::timestamptz <= $2::timestamptz
  AND t.TenantId = $3
ORDER BY
  t.CreDtTm::timestamptz DESC
LIMIT $4 -- This part is added dynamically if maxQueryLimit is configured
```
**Purpose**: The query retrieves an ordered list of the most recent **source account IDs** (creditors) that have successfully sent funds to the specified `debtorAccountId`.

**Result**: A list of creditor account IDs, ordered from most recent to least recent.

---
## Post-Query Logic
The core logic of this rule happens in the application code after the query returns a list of source accounts.
1. It takes the most recent creditor ID from the list as the reference.
2. It then iterates through the rest of the list and counts how many of the source IDs are **identical** to that most recent one.
3. This final `countOfMatchingCreditors` is the value that gets scored. For example, if the last 5 transactions to Debtor A were all from Creditor X, the count would be 5.

---

## ISO 20022 Message Types Used

| Code | Full Name | Role in Rule |
|---|---|---|
| `pacs.002.001.12`| FI To FI Payment Status Report | Used to identify successful transactions and their `source` (creditor) and `destination` (debtor). |
| `ACCC` | AcceptedCreditSettlementCompleted | The specific status code that confirms a successful transaction. |

---

## Variable Importance Map
```
debtorAccountId             ──► WHO   (The account receiving funds, being checked for bust-out)
maxQueryLimit               ──► HOW MANY (The number of recent transactions to analyze)
countOfMatchingCreditors    ──► COMPUTED COUNT of sequential payments from the same source
```
The `countOfMatchingCreditors` is the **final output** fed into `determineOutcome()`.

## How to Implement This in Your Application
### Database Table: transaction
```sql
CREATE TABLE transaction (
  id          SERIAL PRIMARY KEY,
  EndToEndId  VARCHAR(35)  NOT NULL,
  source      VARCHAR(50)  NOT NULL, -- The creditor account
  destination VARCHAR(50)  NOT NULL, -- The debtor account
  TxTp        VARCHAR(30)  NOT NULL,
  TxSts       VARCHAR(10),
  CreDtTm     TIMESTAMPTZ  NOT NULL,
  TenantId    VARCHAR(50)  NOT NULL,

  INDEX idx_dest_txtp_txsts_tenant (destination, TxTp, TxSts, TenantId)
);
```
### Your Rule Config Structure
```json
{
  "config": {
    "parameters": {
      "maxQueryLimit": 5
    },
    "exitConditions": [
      { "subRuleRef": ".x00", "reason": "Transaction was not successful" },
      { "subRuleRef": ".x01", "reason": "Insufficient transaction history for analysis (less than 2)" }
    ],
    "bands": [
      { "subRuleRef": ".01", "lowerLimit": 3, "upperLimit": 5 },   // 3-4 sequential payments from the same source
      { "subRuleRef": ".02", "lowerLimit": 5, "upperLimit": null } // 5+ sequential payments from the same source
    ]
  }
}
```
### Your determineOutcome Function
```javascript
function determineOutcome(countOfMatchingCreditors, ruleConfig, ruleResult) {
  const band = ruleConfig.config.bands.find(b =>
    countOfMatchingCreditors >= b.lowerLimit &&
    (b.upperLimit === null || countOfMatchingCreditors < b.upperLimit)
  );

  if (!band) {
    return { ...ruleResult, subRuleRef: '.00', reason: 'No concentrated payment pattern detected.' };
  }

  return {
    ...ruleResult,
    subRuleRef: band.subRuleRef,
    reason: `Detected ${countOfMatchingCreditors} sequential payments from the same creditor, indicating a potential bust-out pattern.`
  };
}
```

### Data Flow Summary
```
Incoming pacs.002 transaction
        │
        ▼
Extract debtorAccountId
        │
        ▼
Query DB ──► Get a list of the last 'maxQueryLimit' source
        │     (creditor) IDs for payments to that debtor.
        │
        ├── History < 2 transactions ──► Exit .x01
        │
        └── Has list of IDs ──► In code, count how many of the IDs
                                in the list match the most recent one.
                                     │
                                     ▼
                              Match the count against bands
                                     │
                                     ▼
                              Return risk subRuleRef (.01/.02)
```
### Key Insight
This rule is highly effective at spotting a common fraud pattern where a compromised account is emptied via a series of rapid, successive payments to a single mule account. Normal payment behavior is typically more varied. A high concentration of payments from one source to one destination in a short time frame is a significant anomaly that this rule is designed to catch.

Each rule is documented with its business logic, detection algorithm, configurable parameters, required KYC and core banking data inputs, cache requirements, regulatory references, and alert output specifications. and also db design for that specific rule the fields and the variables configurations.
