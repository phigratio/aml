# Full Breakdown of Rule-002

## What This Rule Does
This rule counts the number of successfully settled (`ACCC`) payment status reports (`pacs.002.001.12`) originating from a specific debtor account within a configurable time window leading up to the current transaction. This helps gauge the recent volume of successful transactions for a given debtor.

## SQL Query — Deep Dive
### Parameters Passed In
| Parameter | Value | Purpose |
|---|---|---|
| $1 | `debtorAccountId` | Filter transactions by the debtor's account ID. |
| $2 | `pacs002CreDtTm` | The timestamp of the current transaction, marking the end of the query window. |
| $3 | `maxQueryRange` | A duration in milliseconds to define the lookback period from the current transaction time. |
| $4 | `tenantId` | Multi-tenant isolation. |

### Query Logic
The rule uses a single, direct SQL query without any Common Table Expressions (CTEs).
```sql
SELECT
  COUNT(*)::bigint AS "length"
FROM
  transaction
WHERE
  source = $1
  AND TxTp = 'pacs.002.001.12'
  AND TxSts = 'ACCC'
  AND CreDtTm::timestamptz BETWEEN $2::timestamptz - (
    $3::bigint * interval '1 millisecond'
  )
  AND $2::timestamptz
  AND TenantId = $4;
```
**Purpose**: To count how many transactions meet the following criteria:
1.  Originated from the specified `debtorAccountId` (`source = $1`).
2.  Are payment status reports (`TxTp = 'pacs.002.001.12'`).
3.  Were successfully settled (`TxSts = 'ACCC'`).
4.  Occurred within the dynamic time window calculated as `[`current transaction time` - `maxQueryRange`] and `current transaction time`].
5.  Belong to the correct `tenantId`.

**Result**: A single value `length` representing the total count of matching transactions.

---

## ISO 20022 Message Types Used

| Code | Full Name | Role in Rule |
|---|---|---|
| `pacs.002.001.12` | FI To FI Payment Status Report | The specific transaction type being counted. |
| `ACCC` | AcceptedCreditSettlementCompleted | The status code confirming the transaction was successfully settled. |

---

## Variable Importance Map
```
debtorAccountId   ──► WHO   (which account are we analyzing)
pacs002CreDtTm    ──► WHEN  (the anchor point in time for the analysis)
maxQueryRange     ──► HOW   (a configuration parameter defining the lookback period)
tenantId          ──► WHERE (which client/tenant in a multi-tenant system)

count             ──► RAW RESULT from DB (the number of transactions found)
```
The `count` is the **final output** fed into `determineOutcome()`, which compares it against the configured `bands`.

## Data Requirements

### Configurable Parameters
| Parameter | Type | Description |
|---|---|---|
| `maxQueryRange` | `number` | The lookback period in milliseconds. |
| `bands` | `Array` | The array of count-based bands used to score the result. |

### Required KYC & Core Banking Data
| Field | Path | Description |
|---|---|---|
| `CreDtTm` | `req.transaction.FIToFIPmtSts.GrpHdr.CreDtTm` | The creation time of the current transaction. |
| `TenantId` | `req.transaction.TenantId` | The identifier for the tenant. |

### Cache Requirements
| Field | Path | Description |
|---|---|---|
| `dbtrAcctId`| `req.DataCache.dbtrAcctId` | The account ID of the debtor. |

## How to Implement This in Your Application
### Database Table: transaction
```sql
CREATE TABLE transaction (
  id          SERIAL PRIMARY KEY,
  EndToEndId  VARCHAR(35)  NOT NULL,
  source      VARCHAR(50)  NOT NULL,   -- account ID (debtor account in this case)
  destination VARCHAR(50),
  TxTp        VARCHAR(30)  NOT NULL,   -- message type: 'pacs.002.001.12'
  TxSts       VARCHAR(10),             -- status: 'ACCC'
  CreDtTm     TIMESTAMPTZ  NOT NULL,   -- message creation datetime
  TenantId    VARCHAR(50)  NOT NULL,   -- tenant identifier
  Amt         NUMERIC(18, 2),

  -- indexes critical for performance
  INDEX idx_source_txtp_txsts_tenant (source, TxTp, TxSts, TenantId)
);
```
### Your Rule Config Structure
```json
{
  "config": {
    "parameters": {
      "maxQueryRange": 604800000   -- 7 days in milliseconds
    },
    "bands": [
      { "subRuleRef": ".01", "lowerLimit": 50, "upperLimit": 100 },
      { "subRuleRef": ".02", "lowerLimit": 100, "upperLimit": 200 },
      { "subRuleRef": ".03", "lowerLimit": 200, "upperLimit": null }
    ]
  }
}
```
### Your determineOutcome Function
```javascript
function determineOutcome(count, ruleConfig, ruleResult) {
  const band = ruleConfig.config.bands.find(b =>
    count >= b.lowerLimit &&
    (b.upperLimit === null || count < b.upperLimit)
  );

  if (!band) {
    // Optional: handle cases where the count doesn't fall into any band
    return { ...ruleResult, subRuleRef: '.x99', reason: 'Count outside defined bands' };
  }

  return {
    ...ruleResult,
    subRuleRef: band.subRuleRef,
    reason: band.reason ?? `Transaction count ${count} falls in band ${band.subRuleRef}`
  };
}
```

### Data Flow Summary
```
Incoming pacs.002 transaction
        │
        ▼
Extract debtorAccountId + timestamp + tenantId
        │
        ▼
Query DB ──► Count previous successful pacs.002s from that debtor
        │     within the configured `maxQueryRange`.
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
This rule assesses the recent transaction frequency for a debtor. A sudden spike or an unusually high number of successful payment status reports within a short period could be an indicator of anomalous or suspicious activity that warrants further investigation.

Each rule is documented with its business logic, detection algorithm, configurable parameters, required KYC and core banking data inputs, cache requirements, regulatory references, and alert output specifications. and also db design for that specific rule the fields and the variables configurations.
