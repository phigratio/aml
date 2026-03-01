# Full Breakdown of Rule-017

## What This Rule Does
This rule counts the total number of successfully settled (`ACCC`) payment status reports (`pacs.002.001.12`) received by a specific **debtor** account within a configurable time window. It is functionally similar to Rule-002 but uses a more semantically precise query by filtering on the `destination` account. The rule is used to measure the volume of recent incoming activity for a debtor.

## SQL Query — Deep Dive
### Parameters Passed In
| Parameter | Value | Purpose |
|---|---|---|
| $1 | `debtorAccountId` | The ID of the **debtor** account receiving the status reports. |
| $2 | `currentPacs002TimeFrame`| The timestamp of the current transaction, marking the end of the query window. |
| $3 | `maxQueryRange` | A duration in milliseconds to define the lookback period from the current transaction time. |
| $4 | `tenantId` | Multi-tenant isolation. |

### Query Logic
The rule uses a single, direct SQL query to count transactions.
```sql
SELECT
  COUNT(*) AS "length"
FROM
  transaction
WHERE
  destination = $1
  AND TxTp = 'pacs.002.001.12'
  AND TxSts = 'ACCC'
  AND TenantID = $4
  AND 
    CreDtTm::timestamptz
        BETWEEN 
            $2::timestamptz - ($3::bigint * interval '1 millisecond')
        AND $2::timestamptz;
```
**Purpose**: To count how many transaction events meet the following criteria:
1.  Were sent to the specified `debtorAccountId` (`destination = $1`). This is a more precise way to model incoming activity to a debtor than checking the `source`.
2.  Are payment status reports (`TxTp = 'pacs.002.001.12'`).
3.  Were successfully settled (`TxSts = 'ACCC'`).
4.  Occurred within the dynamic time window defined by `maxQueryRange`.

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
debtorAccountId    ──► WHO   (Which account's incoming activity are we analyzing)
maxQueryRange      ──► HOW   (A configuration parameter defining the lookback period)
count              ──► RAW RESULT from DB (The number of transactions found)
```
The `count` is the **final output** fed into `determineOutcome()` for risk evaluation.

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
  source      VARCHAR(50)  NOT NULL,
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
      "maxQueryRange": 86400000   -- 24 hours in milliseconds
    },
    "bands": [
      { "subRuleRef": ".01", "lowerLimit": 100, "upperLimit": 250 },
      { "subRuleRef": ".02", "lowerLimit": 250, "upperLimit": 500 },
      { "subRuleRef": ".03", "lowerLimit": 500, "upperLimit": null }
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
    return { ...ruleResult, subRuleRef: '.00', reason: 'Debtor activity volume within normal limits.' };
  }

  return {
    ...ruleResult,
    subRuleRef: band.subRuleRef,
    reason: `Debtor transaction count ${count} falls in high-activity band ${band.subRuleRef}`
  };
}
```

### Data Flow Summary
```
Incoming pacs.002 transaction
        │
        ▼
Extract debtorAccountId + timestamp
        │
        ▼
Query DB ──► Count previous successful pacs.002s where the DEBTOR was the destination
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
This rule provides a clear measure of the volume of incoming transaction activity for a debtor. A high count can be an indicator of several suspicious scenarios, most notably that the account is being used as a "mule" or collection point for funds from numerous, potentially illicit, sources. Monitoring this velocity is a fundamental check in AML systems.

Each rule is documented with its business logic, detection algorithm, configurable parameters, required KYC and core banking data inputs, cache requirements, regulatory references, and alert output specifications. and also db design for that specific rule the fields and the variables configurations.
