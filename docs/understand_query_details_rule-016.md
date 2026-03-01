# Full Breakdown of Rule-016

## What This Rule Does
This rule is the direct counterpart to Rule-002, but it focuses on the **creditor** account. It counts the number of successfully settled (`ACCC`) payment status reports (`pacs.002.001.12`) originating from a specific creditor account within a configurable time window. This serves as a measure of the creditor's recent outgoing transaction volume.

## SQL Query — Deep Dive
### Parameters Passed In
| Parameter | Value | Purpose |
|---|---|---|
| $1 | `creditorAccountId` | The ID of the **creditor** account to filter transactions by. |
| $2 | `currentPacs002TimeFrame`| The timestamp of the current transaction, marking the end of the query window. |
| $3 | `maxQueryRange` | A duration in milliseconds to define the lookback period from the current transaction time. |
| $4 | `tenantId` | Multi-tenant isolation. |

### Query Logic
The rule uses a single, direct SQL query. This is structurally identical to the query in Rule-002, but parameterized with the creditor's account ID.
```sql
SELECT
  COUNT(*) AS "length"
FROM
  transaction
WHERE
  source = $1
  AND TxTp = 'pacs.002.001.12'
  AND TxSts = 'ACCC'
  AND  
    CreDtTm::timestamptz 
      BETWEEN 
        $2::timestamptz - ($3::bigint * interval '1 millisecond')
      AND
        $2::timestamptz
  AND TenantId = $4;
```
**Purpose**: To count how many transactions meet the following criteria:
1.  Originated from the specified `creditorAccountId` (`source = $1`).
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
creditorAccountId  ──► WHO   (Which account are we analyzing)
maxQueryRange      ──► HOW   (A configuration parameter defining the lookback period)
count              ──► RAW RESULT from DB (The number of transactions found)
```
The `count` is the **final output** fed into `determineOutcome()` for risk evaluation.

## How to Implement This in Your Application
The implementation details are identical to Rule-002, with the understanding that the logic is being applied to the creditor account.

### Database Table: transaction
```sql
CREATE TABLE transaction (
  id          SERIAL PRIMARY KEY,
  EndToEndId  VARCHAR(35)  NOT NULL,
  source      VARCHAR(50)  NOT NULL,   -- The creditor account
  destination VARCHAR(50),
  TxTp        VARCHAR(30)  NOT NULL,
  TxSts       VARCHAR(10),
  CreDtTm     TIMESTAMPTZ  NOT NULL,
  TenantId    VARCHAR(50)  NOT NULL,

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
    return { ...ruleResult, subRuleRef: '.00', reason: 'Creditor activity volume within normal limits.' };
  }

  return {
    ...ruleResult,
    subRuleRef: band.subRuleRef,
    reason: `Creditor transaction count ${count} falls in high-activity band ${band.subRuleRef}`
  };
}
```

### Data Flow Summary
```
Incoming pacs.002 transaction
        │
        ▼
Extract creditorAccountId + timestamp
        │
        ▼
Query DB ──► Count previous successful pacs.002s from that CREDITOR
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
This rule monitors the volume of outgoing payments from a single source. While Rule-002 looks at the receiver, this rule scrutinizes the sender. A high volume of recent, successful transactions originating from one creditor can be an important indicator of suspicious activity, such as a business account being used for unexpected mass payouts, or a compromised personal account being used in a "fan-out" fraud scheme to distribute funds.

Each rule is documented with its business logic, detection algorithm, configurable parameters, required KYC and core banking data inputs, cache requirements, regulatory references, and alert output specifications. and also db design for that specific rule the fields and the variables configurations.
