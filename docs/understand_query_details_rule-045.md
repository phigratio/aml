# Full Breakdown of Rule-045

## What This Rule Does
This rule is the counterpart to Rule-044. It provides a simple lifetime count of all successful transactions sent *from* a **creditor** account. This serves as a basic measure of the sending account's overall historical activity and maturity.

## SQL Query — Deep Dive
### Parameters Passed In
| Parameter | Value | Purpose |
|---|---|---|
| $1 | `creditorAccountId` | The ID of the creditor account sending the transactions. |
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
  source = $1
  AND TxTp = 'pacs.002.001.12'
  AND TxSts = 'ACCC'
  AND CreDtTm::timestamptz <= $2::timestamptz
  AND TenantId = $3;
```
**Purpose**: To get a total lifetime count of all past transactions that originated from the specified `creditorAccountId` and were successfully settled (`ACCC`).

**Result**: A single value, `numberOfSuccessfulTransactions`, representing the total volume of successful outgoing transactions from this creditor.

---

## ISO 20022 Message Types Used

| Code | Full Name | Role in Rule |
|---|---|---|
| `pacs.002.001.12` | FI To FI Payment Status Report | The transaction type being counted. |
| `ACCC` | AcceptedCreditSettlementCompleted | The status code confirming success. |

---

## Variable Importance Map
```
creditorAccountId ──► WHO (The account being measured)
count             ──► COMPUTED COUNT (The total number of outgoing transactions)
```

## Data Requirements

### Configurable Parameters
| Parameter | Type | Description |
|---|---|---|
| `bands` | `Array` | The array of count-based bands for scoring the account's maturity. |

### Required KYC & Core Banking Data
| Field | Path | Description |
|---|---|---|
| `CreDtTm` | `req.transaction.FIToFIPmtSts.GrpHdr.CreDtTm` | The creation time of the current transaction. |
| `TenantId` | `req.transaction.TenantId` | The identifier for the tenant. |

### Cache Requirements
| Field | Path | Description |
|---|---|---|
| `cdtrAcctId`| `req.DataCache.cdtrAcctId` | The account ID of the creditor. |

## How to Implement This in Your Application
### Database Table: transaction
```sql
CREATE TABLE transaction (
  id          SERIAL PRIMARY KEY,
  source      VARCHAR(50)  NOT NULL, -- The creditor account
  TxTp        VARCHAR(30)  NOT NULL,
  TxSts       VARCHAR(10),
  CreDtTm     TIMESTAMPTZ  NOT NULL,
  TenantId    VARCHAR(50)  NOT NULL,
  -- An index on the queried fields is critical for performance
  INDEX idx_source_txtp_txsts_tenant (source, TxTp, TxSts, TenantId)
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
This rule provides a foundational metric for the sending account's maturity and activity level. An account sending funds that has very little of its own transaction history is inherently riskier than an established account. This simple count provides essential context that can be used by other rules to assess the overall risk of a payment.

Each rule is documented with its business logic, detection algorithm, configurable parameters, required KYC and core banking data inputs, cache requirements, regulatory references, and alert output specifications. and also db design for that specific rule the fields and the variables configurations.
