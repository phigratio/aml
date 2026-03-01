# Full Breakdown of Rule-074

## What This Rule Does
This is a geolocation-based rule designed to detect "impossible velocity" or "superman" fraud. It calculates the speed of travel required to account for the location of a debtor's two most recent transactions. If the calculated velocity is faster than a plausible speed (e.g., faster than a commercial flight), it flags the transaction as high-risk, suggesting that the same person or device could not have legitimately initiated both payments.

## SQL Query — Deep Dive
### Parameters Passed In
| Parameter | Value | Purpose |
|---|---|---|
| $1 | `endToEndId` | The `EndToEndId` of the current transaction, used to find its timestamp. |
| $2 | `dbtrAcctId` | The ID of the debtor account to find historical transaction locations for. |
| $3 | `maxQueryRange` | The lookback period to find the previous transaction. |
| $4 | `tenantId` | For multi-tenant data isolation. |

### Query Logic
This rule queries the raw `pacs008` document store.
```sql
WITH assoc AS (
  SELECT CreDtTm::timestamptz AS assocCreDtTm FROM pacs008 WHERE EndToEndId = $1 AND TenantId = $4 ORDER BY assocCreDtTm DESC LIMIT 1
)
SELECT
  CreDtTm::timestamptz AS "CreDtTm",
  (t.document -> 'FIToFICstmrCdtTrf' -> 'SplmtryData' -> 'Envlp' -> 'Doc' -> 'InitgPty' -> 'Glctn' ->> 'Lat')::double precision AS "lat",
  (t.document -> 'FIToFICstmrCdtTrf' -> 'SplmtryData' -> 'Envlp' -> 'Doc' -> 'InitgPty' -> 'Glctn' ->> 'Long')::double precision AS "long"
FROM pacs008 AS t JOIN assoc ON TRUE
WHERE document -> 'DataCache' ->> 'dbtrAcctId' = $2
  AND TenantId = $4
  AND CreDtTm::timestamptz BETWEEN assoc.assocCreDtTm - ($3::bigint * interval '1 millisecond') AND assoc.assocCreDtTm
ORDER BY CreDtTm::timestamptz DESC
LIMIT 2;
```
**Purpose**:
1.  The `assoc` CTE finds the timestamp of the current transaction.
2.  The main query then finds the current transaction and the one immediately preceding it for the same `dbtrAcctId` within the `maxQueryRange`.
3.  For both transactions, it extracts the Initiation Party's Geolocation Latitude (`Lat`) and Longitude (`Long`) from the supplementary data within the raw `pacs008` JSON document.

**Result**: A list containing two rows, each with the `CreDtTm`, `lat`, and `long` for the debtor's two most recent transactions.

---
## Post-Query Logic
1.  **Haversine Distance**: The `haversineDistance` function is used to calculate the great-circle distance (in kilometers) between the two sets of coordinates.
2.  **Time Difference**: The code calculates the time elapsed between the two transactions in hours.
3.  **Velocity Calculation**: It calculates the velocity: `velocity = distance / hours`.
4.  **Final Evaluation**: This `velocity` (in km/h) is passed to `determineOutcome` to be scored against risk bands.

---
## Variable Importance Map
```
dbtrAcctId  ──► WHO (The account whose movements are being tracked)
Lat, Long   ──► WHERE (The coordinates of the transactions)
CreDtTm     ──► WHEN (The timestamps of the transactions)
velocity    ──► COMPUTED SCORE (The calculated speed between the two points)
```

## How to Implement This in Your Application
### Database Table: pacs008
```sql
CREATE TABLE pacs008 (
  id          SERIAL PRIMARY KEY,
  EndToEndId  VARCHAR(35)  NOT NULL,
  document    JSONB, -- The full pacs.008 message with supplementary geo-data
  TenantId    VARCHAR(50)  NOT NULL,
  -- A GIN index on the debtor account ID within the JSONB is recommended
  INDEX idx_pacs008_doc_dbtracctid ON pacs008 USING GIN ((document -> 'DataCache' ->> 'dbtrAcctId'));
);
```
### Your Rule Config Structure
```json
{
  "config": {
    "parameters": {
      "maxQueryRange": 86400000 // 24 hours
    },
    "exitConditions": [
      { "subRuleRef": ".x01", "reason": "Insufficient location history (less than 2 points)" }
    ],
    "bands": [
      // Velocity in km/h
      { "subRuleRef": ".01", "lowerLimit": 900, "upperLimit": 2000 },   // Faster than a commercial jet
      { "subRuleRef": ".02", "lowerLimit": 2000, "upperLimit": null }    // Implausible speed
    ]
  }
}
```
### Key Insight
Impossible velocity is a classic and highly reliable fraud indicator. It provides strong evidence that an account is either compromised and being used by multiple fraudsters in different locations simultaneously, or that the location data itself is being spoofed and is therefore untrustworthy. This rule is essential for any system that leverages geographical data for risk assessment.

Each rule is documented with its business logic, detection algorithm, configurable parameters, required KYC and core banking data inputs, cache requirements, regulatory references, and alert output specifications. and also db design for that specific rule the fields and the variables configurations.
