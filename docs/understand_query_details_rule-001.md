# Full Breakdown of Rule-001

## What This Rule Does
This rule calculates how long a creditor account has been active by finding the oldest transaction timestamp associated with it, then comparing that age against configured "bands" (time ranges) to determine a risk outcome.

## SQL Query — Deep Dive
### Parameters Passed In
| Parameter | Value | Purpose |
|---|---|---|
| $1 | `creditorAccountId` | Filter transactions by the creditor's account |
| $2 | `currentPacs002TimeFrame` | The current transaction's timestamp (upper bound) |
| $3 | `tenantId` | Multi-tenant isolation |

### CTE 1: oldest_sent
```sql
SELECT MIN(CreDtTm::timestamptz) AS min_sent
FROM transaction
WHERE source = $1              -- creditor's account
  AND TxTp = 'pacs.008.001.10' -- only SENT payments (pacs.008 = credit transfer)
  AND CreDtTm::timestamptz <= $2 -- up to current transaction time
  AND TenantId = $3
```
**Purpose**: Find the earliest time this creditor account ever appeared as a source in any outgoing payment. This gives the "account age from the sending side."

### CTE 2: earliest_success
```sql
SELECT EndToEndId
FROM transaction
WHERE source = $1
  AND TxTp = 'pacs.002.001.12'  -- payment status report
  AND TxSts = 'ACCC'             -- status = "Accepted, Credit to Customer Account" (fully settled)
  AND CreDtTm::timestamptz <= $2
  AND TenantId = $3
ORDER BY CreDtTm::timestamptz ASC
LIMIT 1
```
**Purpose**: Find the EndToEndId of the very first successfully completed transaction for this creditor account. ACCC is the ISO 20022 status meaning fully accepted & settled.

### CTE 3: oldest_success_p008
```sql
SELECT MIN(t.CreDtTm::timestamptz) AS min_recv
FROM transaction t
JOIN earliest_success s USING (EndToEndId)  -- match on the EndToEndId found above
WHERE t.TxTp = 'pacs.008.001.10'
  AND t.TenantId = $3
```
**Purpose**: Take that earliest successful EndToEndId and find the original pacs.008 credit transfer message timestamp that corresponds to it. This gives the "account age from the receiving/success side."

### Final SELECT
```sql
SELECT NULLIF(
  LEAST(
    COALESCE(os.min_sent, 'infinity'::timestamptz),
    COALESCE(orx.min_recv, 'infinity'::timestamptz)
  ),
  'infinity'::timestamptz
) AS "oldestCreDtTm"
FROM oldest_sent os CROSS JOIN oldest_success_p008 orx
```

| Function | What it does |
|---|---|
| `COALESCE(..., 'infinity')` | If either CTE returned NULL (no data), treat it as infinity so LEAST ignores it |
| `LEAST(...)` | Pick the **earlier** of the two timestamps |
| `NULLIF(..., 'infinity')` | If both were NULL (no activity at all), return NULL instead of infinity |

**Result**: The single **oldest verifiable timestamp** of creditor account activity.

---

## ISO 20022 Message Types Used

| Code | Full Name | Role in Rule |
|---|---|---|
| `pacs.008.001.10` | FI To FI Customer Credit Transfer | The actual payment instruction |
| `pacs.002.001.12` | FI To FI Payment Status Report | Confirmation/settlement status |
| `ACCC` | AcceptedCreditSettlementCompleted | Fully settled status code |

---

## Variable Importance Map
```
creditorAccountId  ──► WHO  (which account are we analyzing)
currentPacs002TimeFrame ──► WHEN  (snapshot in time, prevents future data leaking in)
tenantId  ──► WHERE  (which client/tenant in a multi-tenant system)

timeStampOldestSuccessfulPacs008Edge ──► RAW RESULT from DB (oldest activity)
timeDifferenceInMs  ──► COMPUTED AGE of the account in milliseconds
                         = currentTime - oldestActivityTime
```

`timeDifferenceInMs` is the **final output** fed into `determineOutcome()` which checks it against `ruleConfig.config.bands` — e.g.:
```
Band 1: 0–7 days       → high risk  → .01
Band 2: 7–30 days      → medium     → .02
Band 3: 30+ days       → low risk   → .03
Exit .x01: no history  → unknown    → exit early
```
## How to Implement This in Your Application
### Database Table: transaction
```sql
CREATE TABLE transaction (
  id          SERIAL PRIMARY KEY,
  EndToEndId  VARCHAR(35)  NOT NULL,   -- ISO 20022 end-to-end ID, links pacs.008 to pacs.002
  source      VARCHAR(50)  NOT NULL,   -- account ID (creditor account)
  TxTp        VARCHAR(30)  NOT NULL,   -- message type: 'pacs.008.001.10' or 'pacs.002.001.12'
  TxSts       VARCHAR(10),             -- status: 'ACCC', 'RJCT', etc. (only on pacs.002)
  CreDtTm     TIMESTAMPTZ  NOT NULL,   -- message creation datetime
  TenantId    VARCHAR(50)  NOT NULL,   -- tenant identifier

  -- indexes critical for performance
  INDEX idx_source_txtp_tenant (source, TxTp, TenantId),
  INDEX idx_e2eid_txtp_tenant  (EndToEndId, TxTp, TenantId)
);
```
### Your Rule Config Structure
```json
{
  "config": {
    "exitConditions": [
      { "subRuleRef": ".x01", "reason": "No creditor account history found" }
    ],
    "bands": [
      { "subRuleRef": ".01", "lowerLimit": 0,        "upperLimit": 604800000   },
      { "subRuleRef": ".02", "lowerLimit": 604800000, "upperLimit": 2592000000 },
      { "subRuleRef": ".03", "lowerLimit": 2592000000,"upperLimit": null        }
    ]
  }
}
```
### Your determineOutcome Function
```javascript
function determineOutcome(timeDifferenceInMs, ruleConfig, ruleResult) {
  const band = ruleConfig.config.bands.find(b =>
    timeDifferenceInMs >= b.lowerLimit &&
    (b.upperLimit === null || timeDifferenceInMs < b.upperLimit)
  );

  if (!band) throw new Error('No matching band for value: ' + timeDifferenceInMs);

  return {
    ...ruleResult,
    subRuleRef: band.subRuleRef,
    reason: band.reason ?? `Account age ${timeDifferenceInMs}ms falls in band ${band.subRuleRef}`
  };
}
```

### Data Flow Summary
```
Incoming pacs.002 transaction
        │
        ▼
Extract creditorAccountId + timestamp + tenantId
        │
        ▼
Query DB ──► Find oldest pacs.008 activity for that creditor
        │
        ├── NULL result ──► Exit .x01 (no history = suspicious)
        │
        └── Has timestamp ──► Calculate age in ms
                                    │
                                    ▼
                             Match against bands
                                    │
                                    ▼
                             Return risk subRuleRef (.01/.02/.03)
```
### Key Insight
The rule essentially asks: "How old is this creditor account?" — a newly active account receiving a payment is riskier than one with years of history. The dual-CTE approach (checking both sent and successfully received sides) ensures you get the most conservative (earliest) possible date, making the account appear as established as the evidence allows.

Each rule is documented with its business logic, detection algorithm, configurable parameters, required KYC and core banking data inputs, cache requirements, regulatory references, and alert output specifications. and also db design for that specific rule the fields and the variables configurations.
