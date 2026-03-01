# Full Breakdown of Rule-004

## What This Rule Does
This rule is the counterpart to Rule-003 and calculates the "time since last activity" for a **debtor's** account. It finds the most recent transaction timestamp associated with the account (either as a sender or a receiver), excluding the current transaction. This "dormancy" period is then evaluated to identify potentially suspicious payments originating from accounts that have been inactive.

## SQL Query — Deep Dive
The SQL query logic is identical to Rule-003, but it is parameterized with the debtor's account ID.

### Parameters Passed In
| Parameter | Value | Purpose |
|---|---|---|
| $1 | `debtorAccount` | The ID of the **debtor** account being analyzed. |
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
**Purpose**: Finds the timestamp of the most recent outgoing payment (`pacs.008.001.10`) originating from the debtor's account.

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
  CreDtTm::timestamptz ASC
LIMIT
  1
```
**Purpose**: Finds the `EndToEndId` of the most recently settled incoming payment for the debtor account. (Note: While less common for a debtor to be the `source` of a `pacs.002`, this covers all activity where the debtor's account ID is the source identifier).

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
**Purpose**: Finds the timestamp of the original credit transfer (`pacs.008`) corresponding to the most recent successful transaction, giving a complete picture of the last activity time.

### Final SELECT
```sql
SELECT
  NULLIF(
    GREATEST(
      COALESCE(
        ns.max_sent::timestamptz,
        '-infinity'::timestamptz
      ),
      COALESCE(
        nr.max_recv::timestamptz,
        '-infinity'::timestamptz
      )
    ),
    '-infinity'::timestamptz
  ) AS "maxCreDtTm"
FROM
  newest_sent ns
  CROSS JOIN newest_received nr;
```
**Logic**: The final SELECT uses `GREATEST` to find the most recent timestamp between the last sent payment and the last successful transaction, providing a definitive "last activity" time.

**Result**: A single timestamp `maxCreDtTm` representing the last verifiable activity for the debtor account.

---

## ISO 20022 Message Types Used

| Code | Full Name | Role in Rule |
|---|---|---|
| `pacs.008.001.10` | FI To FI Customer Credit Transfer | The core payment message, checked for sending activity. |
| `pacs.002.001.12` | FI To FI Payment Status Report | Used to confirm that a transaction involving the account was successfully settled. |
| `ACCC` | AcceptedCreditSettlementCompleted | The status confirming successful settlement. |

---

## Variable Importance Map
```
debtorAccount          ──► WHO   (The account being analyzed)
currentPacs002TimeFrame  ──► WHEN  (The reference point in time)
endToEndId             ──► WHAT  (The current transaction to be excluded)
tenantId               ──► WHERE (Data isolation)

maxCreDtTm             ──► RAW RESULT from DB (Timestamp of last activity)
timeDifferenceInMs     ──► COMPUTED DORMANCY in milliseconds for the debtor
```
The `timeDifferenceInMs` is the **final output** passed to `determineOutcome()` for risk evaluation.

## How to Implement This in Your Application
The implementation details are identical to Rule-003, with the understanding that the logic is being applied to the debtor account.

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
      { "subRuleRef": ".x01", "reason": "No verifiable debtor account activity detected" }
    ],
    "bands": [
      { "subRuleRef": ".01", "lowerLimit": 2592000000, "upperLimit": null },        -- 30+ days (High Risk)
      { "subRule_ref": ".02", "lowerLimit": 604800000, "upperLimit": 2592000000 }, -- 7-30 days (Medium Risk)
      { "subRuleRef": ".03", "lowerLimit": 0, "upperLimit": 604800000 }           -- 0-7 days (Low Risk)
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
    reason: band.reason ?? `Debtor account dormancy of ${timeDifferenceInMs}ms falls in band ${band.subRuleRef}`
  };
}
```

### Data Flow Summary
```
Incoming pacs.002 transaction
        │
        ▼
Extract debtorAccount + timestamp + EndToEndId
        │
        ▼
Query DB ──► Find the MOST RECENT timestamp of any previous
        │     activity for the DEBTOR, excluding the current tx.
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
This rule provides the same powerful check as Rule-003 but for the other side of the transaction. A payment coming from a long-dormant debtor account is a significant anomaly. It could suggest that a rarely used account has been compromised or that the transaction is an attempt to test a stolen credential before making larger fraudulent payments.

Each rule is documented with its business logic, detection algorithm, configurable parameters, required KYC and core banking data inputs, cache requirements, regulatory references, and alert output specifications. and also db design for that specific rule the fields and the variables configurations.
