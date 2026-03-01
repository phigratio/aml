# Full Breakdown of Rule-044

## What This Rule Does
This rule provides a simple lifetime count of all successful transactions received by a **debtor** account. It serves as a basic measure of the account's overall historical activity and maturity. This rule is functionally identical to Rule-017, but without a `maxQueryRange` parameter, meaning it counts the entire history.

## SQL Query — Deep Dive
### Parameters Passed In
| Parameter | Value | Purpose |
|---|---|---|
| $1 | `debtorAccountId` | The ID of the debtor account receiving the transactions. |
| $2 | `currentPacs002TimeFrame` | The timestamp of the current transaction, used as an upper time limit. |
| $3 | `tenantId` | For multi-tenant data isolation. |

### Query Logic
The rule uses a single, direct `COUNT` query.
```sql
SELECT
  COUNT(*) AS "numberOfSuccessfulTransactions"
FROM
  transaction
WHERE
  destination = $1
  AND TxTp = 'pacs.002.001.12'
  AND TxSts = 'ACCC'
  AND CreDtTm::timestamptz <= $2::timestamptz
  AND TenantId = $3;
```
**Purpose**: To get a total lifetime count of all past transactions that were successfully settled (`ACCC`) and sent to the specified `debtorAccountId`.

**Result**: A single value, `numberOfSuccessfulTransactions`, representing the total volume of successful incoming transactions to this debtor.

---

## ISO 20022 Message Types Used

| Code | Full Name | Role in Rule |
|---|---|---|
| `pacs.002.001.12` | FI To FI Payment Status Report | The transaction type being counted. |
| `ACCC` | AcceptedCreditSettlementCompleted | The status code confirming success. |

---

## Variable Importance Map
```
debtorAccountId   ──► WHO (The account being measured)
count             ──► COMPUTED COUNT (The total number of incoming transactions)
```

## How to Implement This in Your Application
### Database Table: transaction
```sql
CREATE TABLE transaction (
  id          SERIAL PRIMARY KEY,
  destination VARCHAR(50)  NOT NULL, -- The debtor account
  TxTp        VARCHAR(30)  NOT NULL,
  TxSts       VARCHAR(10),
  CreDtTm     TIMESTAMPTZ  NOT NULL,
  TenantId    VARCHAR(50)  NOT NULL,
  -- An index on the queried fields is critical for performance
  INDEX idx_dest_txtp_txsts_tenant (destination, TxTp, TxSts, TenantId)
);
```
### Your Rule Config Structure
```json
{
  "config": {
    "bands": [
      { "subRuleRef": ".01", "lowerLimit": 0, "upperLimit": 10 },    // Very new/inactive account
      { "subRuleRef": ".02", "lowerLimit": 10, "upperLimit": 100 }, // Moderately active account
      { "subRuleRef": ".03", "lowerLimit": 100, "upperLimit": null }// Established account
    ]
  }
}
```

### Key Insight
This rule provides a foundational metric for understanding an account's maturity. An account with a very low total number of historical transactions is inherently less established and potentially riskier than an account with a long and active history. This count serves as a basic but essential piece of context for other, more complex rules.

Each rule is documented with its business logic, detection algorithm, configurable parameters, required KYC and core banking data inputs, cache requirements, regulatory references, and alert output specifications. and also db design for that specific rule the fields and the variables configurations.
