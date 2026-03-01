# Full Breakdown of Rule-003

## What This Rule Does
This rule calculates the "time since last activity" for a creditor's account. It finds the most recent transaction timestamp associated with the account (either sent or successfully received), excluding the current transaction. This "dormancy" period is then evaluated against configured time bands to identify potentially suspicious activity on accounts that have been inactive.

## SQL Query — Deep Dive
### Parameters Passed In
| Parameter | Value | Purpose |
|---|---|---|
| $1 | `creditorAccountId` | The ID of the creditor account being analyzed. |
| $2 | `currentPacs002TimeFrame` | The timestamp of the current transaction, serving as an upper boundary for historical searches. |
| $3 | `endToEndId` | The End-to-End ID of the current transaction, used to exclude it from the historical analysis. |
| $4 | `tenantId` | For multi-tenant data isolation. |

### CTE 1: newest_sent
```sql
SELECT
  MAX(CreDtTm::timestamptz) AS max_sent
FROM
  transaction
WHERE
  source = $1
  AND TxTp = 'pacs.008.001.10'
  AND EndToEndId <> $3
  AND CreDtTm::timestamptz < $2::timestamptz
  AND TenantId = $4
```
**Purpose**: Finds the timestamp of the most recent outgoing payment (`pacs.008.001.10`) from the creditor's account, ensuring it's not the current transaction.

### CTE 2: all_success
```sql
SELECT
  EndToEndId
FROM
  transaction
WHERE
  source = $1
  AND TxTp = 'pacs.002.001.12'
  AND TxSts = 'ACCC'
  AND EndToEndId <> $3
  AND CreDtTm::timestamptz < $2::timestamptz
  AND TenantId = $4
ORDER BY
  CreDtTm::timestamptz DESC
LIMIT
  1
```
**Purpose**: Finds the `EndToEndId` of the most recently settled (`ACCC`) incoming payment for the creditor account, excluding the current transaction.

### CTE 3: newest_received
```sql
SELECT
  MAX(t.CreDtTm::timestamptz) AS max_recv
FROM
  transaction t
  JOIN all_success s USING (EndToEndId)
WHERE
  t.TxTp = 'pacs.008.001.10'
  AND t.TenantId = $4
```
**Purpose**: Joins with the `all_success` CTE to find the timestamp of the original credit transfer (`pacs.008`) that corresponds to the most recent successful receipt. This gives the timestamp of the last *successfully received* payment.

### Final SELECT
```sql
SELECT
  NULLIF(
    GREATEST(
      COALESCE(ns.max_sent, '-infinity'::timestamptz),
      COALESCE(nr.max_recv, '-infinity'::timestamptz)
    ),
    '-infinity'::timestamptz
  ) AS "maxCreDtTm"
FROM
  newest_sent ns
  CROSS JOIN newest_received nr;
```
**Logic**: The final SELECT statement uses `GREATEST` to pick the more recent of the two timestamps (`max_sent` and `max_recv`). `COALESCE` ensures that if one side has no history, it's treated as negative infinity and doesn't affect the result. `NULLIF` returns `NULL` if no history exists at all.

**Result**: A single timestamp `maxCreDtTm` representing the last verifiable activity for the creditor account.

---

## ISO 20022 Message Types Used

| Code | Full Name | Role in Rule |
|---|---|---|
| `pacs.008.001.10` | FI To FI Customer Credit Transfer | The core payment message, checked for both sending and receiving activity. |
| `pacs.002.001.12` | FI To FI Payment Status Report | Used to confirm that an incoming payment was successfully settled. |
| `ACCC` | AcceptedCreditSettlementCompleted | The status confirming successful settlement. |

---

## Variable Importance Map
```
creditorAccountId      ──► WHO   (The account being analyzed)
currentPacs002TimeFrame  ──► WHEN  (The reference point in time)
endToEndId             ──► WHAT  (The current transaction to be excluded)
tenantId               ──► WHERE (Data isolation)

maxCreDtTm             ──► RAW RESULT from DB (Timestamp of last activity)
timeDifferenceInMs     ──► COMPUTED DORMANCY in milliseconds
```
The `timeDifferenceInMs` is the **final output** passed to `determineOutcome()` for risk evaluation.

## How to Implement This in Your Application
### Database Table: transaction
```sql
CREATE TABLE transaction (
  id          SERIAL PRIMARY KEY,
  EndToEndId  VARCHAR(35)  NOT NULL,
  source      VARCHAR(50)  NOT NULL,
  TxTp        VARCHAR(30)  NOT NULL,
  TxSts       VARCHAR(10),
  CreDtTm     TIMESTAMPTZ  NOT NULL,
  TenantId    VARCHAR(50)  NOT NULL,

  INDEX idx_source_txtp_credtm_tenant (source, TxTp, CreDtTm, TenantId)
);
```
### Your Rule Config Structure
```json
{
  "config": {
    "exitConditions": [
      { "subRuleRef": ".x01", "reason": "No verifiable creditor account activity detected" }
    ],
    "bands": [
      { "subRuleRef": ".01", "lowerLimit": 2592000000, "upperLimit": null },        -- 30+ days
      { "subRuleRef": ".02", "lowerLimit": 604800000, "upperLimit": 2592000000 }, -- 7-30 days
      { "subRuleRef": ".03", "lowerLimit": 0, "upperLimit": 604800000 }           -- 0-7 days
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

  if (!band) {
    return { ...ruleResult, subRuleRef: '.x99', reason: 'Dormancy period outside defined bands' };
  }

  return {
    ...ruleResult,
    subRuleRef: band.subRuleRef,
    reason: band.reason ?? `Account dormancy of ${timeDifferenceInMs}ms falls in band ${band.subRuleRef}`
  };
}
```

### Data Flow Summary
```
Incoming pacs.002 transaction
        │
        ▼
Extract creditorAccountId + timestamp + EndToEndId
        │
        ▼
Query DB ──► Find the MOST RECENT timestamp of any previous
        │     activity (sent or received), excluding current tx.
        │
        ├── NULL result ──► Exit .x01 (no history)
        │
        └── Has timestamp ──► Calculate dormancy in ms
                                    │ (CurrentTime - LastActivityTime)
                                    ▼
                             Match against bands
                                    │
                                    ▼
                             Return risk subRuleRef (.01/.02/.03)
```
### Key Insight
This rule flags transactions on accounts that have been inactive for a significant period. A sudden transaction on a dormant account is a classic red flag for account takeover or other forms of fraud. By checking both sent and received history, the rule gets a comprehensive view of the account's true last activity date.

Each rule is documented with its business logic, detection algorithm, configurable parameters, required KYC and core banking data inputs, cache requirements, regulatory references, and alert output specifications. and also db design for that specific rule the fields and the variables configurations.
