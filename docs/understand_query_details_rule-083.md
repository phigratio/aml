# Full Breakdown of Rule-083

## What This Rule Does
This is a KYC (Know Your Customer) oriented rule that assesses the risk associated with a **debtor** entity based on the number of accounts they hold. It counts how many distinct payment accounts are linked to the single, legal `debtorId`. An entity holding an unusually large number of accounts can be a red flag for attempts to create shell companies, obfuscate fund flows, or structure complex laundering networks.

## SQL Query — Deep Dive
### Parameters Passed In
| Parameter | Value | Purpose |
|---|---|---|
| $1 | `debtorId` | The unique legal identifier for the debtor entity. |
| $2 | `currentPacs002TimeFrame` | The timestamp of the current transaction, used as an upper time limit. |
| $3 | `tenantId` | For multi-tenant data isolation. |

### Query Logic
The rule uses a single, direct `COUNT` query against a dedicated `account_holder` table.
```sql
SELECT
  COUNT(*) AS "numberOfAccounts"
FROM
  account_holder AS accounts
WHERE
  accounts.source = $1
  AND accounts.CreDtTm::timestamptz <= $2
  AND accounts.TenantId = $3;
```
**Purpose**: To get a total count of all account records associated with the specified `debtorId`. This assumes an `account_holder` table exists where `source` holds the entity ID.

**Result**: A single value, `numberOfAccounts`, representing the total number of accounts held by that debtor entity.

---
## Variable Importance Map
```
debtorId           ──► WHO (The legal entity being analyzed)
numberOfAccounts   ──► COMPUTED COUNT (How many accounts this entity holds)
```

## Data Requirements

### Configurable Parameters
| Parameter | Type | Description |
|---|---|---|
| `bands` | `Array` | The array of count-based bands for scoring. |

### Required KYC & Core Banking Data
| Field | Path | Description |
|---|---|---|
| `CreDtTm` | `req.transaction.FIToFIPmtSts.GrpHdr.CreDtTm` | The creation time of the current transaction. |
| `TenantId` | `req.transaction.TenantId` | The identifier for the tenant. |

### Cache Requirements
| Field | Path | Description |
|---|---|---|
| `dbtrId`| `req.DataCache.dbtrId` | The legal entity ID of the debtor. |

## How to Implement This in Your Application
### Database Table: account_holder
This rule requires a table that maps legal entity IDs to their associated payment accounts.
```sql
CREATE TABLE account_holder (
  id          SERIAL PRIMARY KEY,
  source      VARCHAR(50)  NOT NULL, -- The legal entity ID (e.g., debtorId)
  accountId   VARCHAR(50)  NOT NULL, -- The specific payment account number
  CreDtTm     TIMESTAMPTZ  NOT NULL, -- The date the relationship was created
  TenantId    VARCHAR(50)  NOT NULL,

  INDEX idx_accountholder_source_tenant (source, TenantId)
);
```
### Your Rule Config Structure
```json
{
  "config": {
    "bands": [
      { "subRuleRef": ".01", "lowerLimit": 5, "upperLimit": 10 },    // 5-9 accounts held by one entity
      { "subRuleRef": ".02", "lowerLimit": 10, "upperLimit": 20 },   // 10-19 accounts
      { "subRuleRef": ".03", "lowerLimit": 20, "upperLimit": null }  // 20+ accounts
    ]
  }
}
```

### Key Insight
This rule helps move risk analysis beyond a single account to the entity level. While a single account may appear normal, discovering that the entity behind it holds 50 other accounts immediately changes the risk profile. This is a fundamental check for identifying sophisticated financial crime structures that rely on a wide network of accounts to move and obscure funds.

Each rule is documented with its business logic, detection algorithm, configurable parameters, required KYC and core banking data inputs, cache requirements, regulatory references, and alert output specifications. and also db design for that specific rule the fields and the variables configurations.
