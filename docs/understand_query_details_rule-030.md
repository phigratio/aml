# Full Breakdown of Rule-030

## What This Rule Does
This is a relationship-based rule that measures the strength of the transaction history between a specific creditor-debtor pair. It counts the total number of successful transactions that have ever occurred between the two parties involved in the current transaction. A low count indicates a new or weak relationship, which can carry a higher risk than a relationship with a long and established history.

## SQL Query — Deep Dive
### Parameters Passed In
| Parameter | Value | Purpose |
|---|---|---|
| $1 | `creditorAccountId` | The ID of the source (creditor) account. |
| $2 | `debtorAccountId` | The ID of the destination (debtor) account. |
| $3 | `currentPacs002TimeFrame`| The timestamp of the current transaction, serving as an upper time limit for the history. |
| $4 | `tenantId` | For multi-tenant data isolation. |

### Query Logic
The rule uses a single, direct SQL query to count the historical transactions between the two parties.
```sql
SELECT
  COUNT(*) AS "numberOfSuccessfulTransactions"
FROM
  transaction
WHERE
  source = $1
  AND destination = $2
  AND TxTp = 'pacs.002.001.12'
  AND TxSts = 'ACCC'
  AND CreDtTm::timestamptz <= $3::timestamptz
  AND TenantId = $4;
```
**Purpose**: To get a total count of all past transactions where the `source` and `destination` accounts exactly match the creditor and debtor from the current transaction, and which were successfully settled.

**Result**: A single value, `numberOfSuccessfulTransactions`, representing the historical volume of successful transactions between these two specific parties.

---

## ISO 20022 Message Types Used

| Code | Full Name | Role in Rule |
|---|---|---|
| `pacs.002.001.12`| FI To FI Payment Status Report | Used to identify and count successfully completed transactions between the pair. |
| `ACCC` | AcceptedCreditSettlementCompleted | The status code that confirms a transaction was successful. |

---

## Variable Importance Map
```
creditorAccountId  ──► WHO (The sending party in the relationship)
debtorAccountId    ──► WHO (The receiving party in the relationship)
count              ──► COMPUTED COUNT (The number of past successful transactions)
```
A low `count` generally signifies a higher risk.

## How to Implement This in Your Application
### Database Table: transaction
```sql
CREATE TABLE transaction (
  id          SERIAL PRIMARY KEY,
  EndToEndId  VARCHAR(35)  NOT NULL,
  source      VARCHAR(50)  NOT NULL,
  destination VARCHAR(50)  NOT NULL,
  TxTp        VARCHAR(30)  NOT NULL,
  TxSts       VARCHAR(10),
  CreDtTm     TIMESTAMPTZ  NOT NULL,
  TenantId    VARCHAR(50)  NOT NULL,
  -- A composite index is critical for the performance of this query
  INDEX idx_src_dest_txtp_txsts_tenant (source, destination, TxTp, TxSts, TenantId)
);
```
### Your Rule Config Structure
```json
{
  "config": {
    "exitConditions": [
      { "subRuleRef": ".x00", "reason": "Transaction was not successful" }
    ],
    "bands": [
      { "subRuleRef": ".01", "lowerLimit": 0, "upperLimit": 1 },   // First transaction ever (highest risk)
      { "subRuleRef": ".02", "lowerLimit": 1, "upperLimit": 5 },  // 1-4 prior transactions (medium risk)
      { "subRuleRef": ".03", "lowerLimit": 5, "upperLimit": null } // 5+ prior transactions (lower risk)
    ]
  }
}
```

### Data Flow Summary
```
Incoming pacs.002 transaction
        │
        ▼
Extract creditorAccountId and debtorAccountId
        │
        ▼
Query DB ──► Count all previous successful transactions
        │     between this specific creditor and debtor.
        │
        ▼
  Receive count
        │
        ▼
 Match against bands
        │
        ▼
 Return risk subRuleRef (.01/.02/.03)
```
### Key Insight
This rule provides fundamental risk context based on transactional history. The very first payment between two entities is inherently riskier than the 100th payment. By quantifying the strength of this relationship, the rule helps distinguish between new, untested payment corridors and well-established, trusted ones. A low count can significantly increase the risk score of a transaction, especially when combined with other high-risk indicators.

Each rule is documented with its business logic, detection algorithm, configurable parameters, required KYC and core banking data inputs, cache requirements, regulatory references, and alert output specifications. and also db design for that specific rule the fields and the variables configurations.
