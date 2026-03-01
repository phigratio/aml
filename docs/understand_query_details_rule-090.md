# Full Breakdown of Rule-090

## What This Rule Does
This is a complex network analysis rule designed to identify potential "gatherer" or "collector" accounts in a money muling network. It looks one step "upstream" from the current debtor to find other accounts that have recently paid it. Then, for each of those upstream accounts, it looks at *their* sources to see how many unique creditors feed into them. The rule flags a transaction if one of its upstream sources is a major collection point receiving funds from a large number of other accounts.

## SQL Query — Deep Dive
### Parameters
| Param | Value | Purpose |
|---|---|---|
| $1 | `debtorAccountId` | The debtor in the current transaction. The rule looks for *other* accounts that also pay this debtor. |
| $2 | `currentPacs002TimeFrame` | The timestamp of the current transaction. |
| $3 | `maxQueryRangeUpstream` | Lookback period to find upstream accounts that paid the current debtor. |
| $4 | `maxQueryRangeDownstream` | Lookback period to analyze the sources of those upstream accounts. |
| $5 | `tenantId` | Multi-tenant isolation. |

### CTE 1: upstreamDebtorAccounts
```sql
SELECT e.destination AS debtor
FROM transaction e
WHERE e.source = $1 AND e.TxTp = 'pacs.002.001.12'
  AND e.CreDtTm::timestamptz BETWEEN $2::timestamptz - ($3::bigint * INTERVAL '1 millisecond') AND $2::timestamptz
GROUP BY e.destination
```
**Purpose**: To find all the unique accounts (`debtor`) that have received money from the current transaction's debtor (`$1`) within the `upstream` time window. This identifies the current debtor's payment partners.

### CTE 2: uniquePairs
```sql
SELECT DISTINCT u.debtor, e.source AS creditor
FROM upstreamDebtorAccounts u JOIN transaction e ON e.destination = u.debtor
WHERE e.TxTp = 'pacs.002.001.12'
  AND e.CreDtTm::timestamptz BETWEEN $2::timestamptz - ($4::bigint * INTERVAL '1 millisecond') AND $2::timestamptz
```
**Purpose**: For each of the `upstreamDebtorAccounts` found, this CTE finds all of *their* unique sources (`creditor`) within the `downstream` time window. This builds a map of the network connections.

### CTE 3: highestCount
```sql
SELECT debtor, COUNT(DISTINCT creditor) AS creditors
FROM uniquePairs
GROUP BY debtor
ORDER BY creditors DESC
LIMIT 1
```
**Purpose**: This aggregates the results, counting the number of unique creditors for each upstream account and identifying the one with the highest count.

### Final SELECT
```sql
SELECT COALESCE(MAX(creditors), 0) AS value
FROM highestCount;
```
**Purpose**: Returns the single highest creditor count found among all the upstream accounts, or 0 if no network was found. This final `value` is the risk score.

---
## Variable Importance Map
```
debtorAccountId  ──► WHO (The anchor point for the network analysis)
value            ──► COMPUTED SCORE (The max number of sources for any upstream account)
```
## Data Requirements

### Configurable Parameters
| Parameter | Type | Description |
|---|---|---|
| `maxQueryRangeUpstream` | `number` | The lookback period (in ms) to find upstream accounts. |
| `maxQueryRangeDownstream`| `number` | The lookback period (in ms) to analyze the sources of those upstream accounts. |

### Required KYC & Core Banking Data
| Field | Path | Description |
|---|---|---|
| `CreDtTm` | `req.transaction.FIToFIPmtSts.GrpHdr.CreDtTm` | The creation time of the current transaction. |
| `TenantId` | `req.transaction.TenantId` | The identifier for the tenant. |

### Cache Requirements
| Field | Path | Description |
|---|---|---|
| `dbtrAcctId`| `req.DataCache.dbtrAcctId` | The account ID of the debtor, used as the starting point for the network analysis. |

## Key Insight
This rule performs a "friend-of-a-friend" style network analysis. It doesn't just look at the current transaction's parties; it investigates their recent partners. The underlying logic is that money laundering networks often use gatherer accounts that collect funds from many low-level sources before passing them on. By flagging a transaction because the debtor was recently paid by a major "gatherer," this rule can detect risk that is not visible by looking at the immediate transaction alone. It's a powerful tool for uncovering hidden relationships in complex payment chains.

Each rule is documented with its business logic, detection algorithm, configurable parameters, required KYC and core banking data inputs, cache requirements, regulatory references, and alert output specifications. and also db design for that specific rule the fields and the variables configurations.
