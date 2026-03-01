# Full Breakdown of Rule-075

## What This Rule Does
This is a geolocation-based rule that identifies if a transaction is originating from a "hotspot" of previous activity. It checks how many of a debtor's historical transactions have occurred within a configured radius (e.g., 10km) of the current transaction's location. A high count indicates the transaction is from a familiar location, while a low count (especially zero) indicates it's from a new, previously unseen location, which can elevate risk.

## SQL Query — Deep Dive
### Parameters Passed In
| Parameter | Value | Purpose |
|---|---|---|
| $1 | `req.DataCache.dbtrAcctId` | The ID of the debtor account to find historical transaction locations for. |
| $2 | `req.DataCache.creDtTm` | The timestamp of the current transaction, used as an upper time limit. |
| $3 | `tenantId` | For multi-tenant data isolation. |

### Query Logic
This rule queries the raw `pacs008` document store to get the locations of all past transactions.
```sql
SELECT
  EndToEndId AS "EndToEndId",
  (document -> 'FIToFICstmrCdtTrf' -> 'SplmtryData' -> 'Envlp' -> 'Doc' -> 'InitgPty' -> 'Glctn' ->> 'Lat')::double precision AS "lat",
  (document -> 'FIToFICstmrCdtTrf' -> 'SplmtryData' -> 'Envlp' -> 'Doc' -> 'InitgPty' -> 'Glctn' ->> 'Long')::double precision AS "lon"
FROM pacs008
WHERE document -> 'DataCache' ->> 'dbtrAcctId' = $1
  AND CreDtTm::timestamptz <= $2::timestamptz
  AND TenantId = $3;
```
**Purpose**: To retrieve the `EndToEndId`, latitude (`lat`), and longitude (`lon`) of all historical transactions associated with the given `dbtrAcctId`.

**Result**: A list of all historical transaction locations for the debtor.

---
## Post-Query Logic
The analysis happens in the application code after the location history is retrieved.
1.  **Isolate Target**: The location of the current transaction is identified from the list.
2.  **Haversine Distance Calculation**: The code iterates through all *other* historical locations in the list. For each one, it uses the `haversineDistance` function to calculate the distance (in km) between the historical point and the current transaction's location.
3.  **Count Nearby Locations**: It counts how many of these calculated distances are less than or equal to the configured `maxRadius`.
4.  **Final Evaluation**: This final count, `withinLocations.length`, is the value passed to `determineOutcome`. A higher count means the location is more familiar.

---
## Variable Importance Map
```
dbtrAcctId         ──► WHO (The account whose movements are being tracked)
maxRadius          ──► HOW (The radius in km to define a "hotspot")
withinLocations    ──► COMPUTED COUNT (Number of past txns in the hotspot)
```

## Data Requirements

### Configurable Parameters
| Parameter | Type | Description |
|---|---|---|
| `maxRadius` | `number` | The radius in kilometers to define a "hotspot". |
| `bands` | `Array` | The array of count-based bands for scoring. |

### Required KYC & Core Banking Data
| Field | Path | Description |
|---|---|---|
| `OrgnlEndToEndId`| `req.transaction.FIToFIPmtSts.TxInfAndSts.OrgnlEndToEndId` | The unique ID of the current transaction. |
| `TenantId` | `req.transaction.TenantId` | The identifier for the tenant. |
| `Lat` / `Long` | `pacs008->...->'InitgPty'->'Glctn'->'Lat'` / `'Long'` | The latitude and longitude from the raw pacs.008 document. |

### Cache Requirements
| Field | Path | Description |
|---|---|---|
| `dbtrAcctId`| `req.DataCache.dbtrAcctId` | The account ID of the debtor. |
| `creDtTm` | `req.DataCache.creDtTm` | The timestamp of the current transaction. |

## How to Implement This in Your Application
### Your Rule Config Structure
```json
{
  "config": {
    "parameters": {
      "maxRadius": 10 // 10km radius
    },
    "exitConditions": [
      { "subRuleRef": ".x01", "reason": "Insufficient location history" }
    ],
    "bands": [
      // This rule is often used to find transactions from NEW locations,
      // so the bands might be set for low counts.
      { "subRuleRef": ".01", "lowerLimit": 0, "upperLimit": 1 } // 0 historical txns within radius (a new location)
    ]
  }
}
```
### Key Insight
This rule helps to establish a geographic baseline for a customer's behavior. Most legitimate customers transact from a few common locations (home, work, etc.). A transaction that originates from a location where the customer has never transacted before is inherently more suspicious. This rule quantifies that suspicion by counting how many times the customer *has* been there before. A count of zero is a strong indicator that the transaction warrants closer scrutiny, especially if it's a high-value payment or has other risk factors.

Each rule is documented with its business logic, detection algorithm, configurable parameters, required KYC and core banking data inputs, cache requirements, regulatory references, and alert output specifications. and also db design for that specific rule the fields and the variables configurations.
