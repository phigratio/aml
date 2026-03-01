# Full Breakdown of Rule-028

## What This Rule Does
This is a straightforward Know Your Customer (KYC) validation rule. It determines the age of the debtor at the time of the transaction and evaluates that age against configured risk bands. Certain age groups (e.g., minors, the elderly) can sometimes be associated with higher-risk scenarios, such as financial abuse or accounts opened for fraudulent purposes.

## SQL Query — Deep Dive
### Parameters Passed In
| Parameter | Value | Purpose |
|---|---|---|
| $1 | `endToEndId` | The End-to-End ID of the current transaction, used to find the original payment document. |
| $2 | `tenantId` | For multi-tenant data isolation. |

### Query Logic
This rule queries the raw `pacs008` document store to extract a piece of KYC data.
```sql
SELECT
  document -> 'FIToFICstmrCdtTrf' -> 'CdtTrfTxInf' -> 'Dbtr' -> 'Id' -> 'PrvtId' -> 'DtAndPlcOfBirth' ->> 'BirthDt' AS "BirthDt"
FROM
  pacs008
WHERE
  EndToEndId = $1
  AND TenantId = $2;
```
**Purpose**:
The query finds the `pacs.008` document corresponding to the current transaction and traverses the JSON structure to extract the debtor's date of birth (`BirthDt`).

**Result**: A single text value representing the debtor's date of birth in 'YYYY-MM-DD' format.

---
## Post-Query Logic
1.  **Validation**: The code validates that the retrieved `BirthDt` is a non-empty string that matches the expected `YYYY-MM-DD` format.
2.  **Age Calculation**: A `calculateAge` function takes the debtor's `BirthDt` and the `transactionDate` to compute the debtor's age in years at the time the transaction occurred.
3.  **Final Evaluation**: This calculated `debtorAge` is the final value passed to `determineOutcome` to be scored against the configured age bands.

---

## ISO 20022 Message Types Used

| Code | Full Name | Role in Rule |
|---|---|---|
| `pacs.008.001.10`| FI To FI Customer Credit Transfer | The raw message stored in the `pacs008` table which contains the Debtor's KYC information like Date of Birth. |

---

## Variable Importance Map
```
endToEndId   ──► WHO (Identifies the transaction to get KYC data from)
BirthDt      ──► WHAT (The raw KYC data point)
debtorAge    ──► COMPUTED VALUE (The age in years at the time of the transaction)
```
The `debtorAge` is the **final output** fed into `determineOutcome()`.

## Data Requirements

### Configurable Parameters
| Parameter | Type | Description |
|---|---|---|
| `bands` | `Array` | The array of age-based bands for scoring. |

### Required KYC & Core Banking Data
| Field | Path | Description |
|---|---|---|
| `OrgnlEndToEndId` | `req.transaction.FIToFIPmtSts.TxInfAndSts.OrgnlEndToEndId` | The unique ID of the current transaction. |
| `CreDtTm` | `req.transaction.FIToFIPmtSts.GrpHdr.CreDtTm` | The creation time of the current transaction, used for the age calculation. |
| `TenantId` | `req.transaction.TenantId` | The identifier for the tenant. |
| `BirthDt` | `pacs008->...->'Dbtr'->'Id'->'PrvtId'->'DtAndPlcOfBirth'->'BirthDt'`| The debtor's date of birth from the raw pacs.008 document. |

### Cache Requirements
| Field | Path | Description |
|---|---|---|
| None | - | This rule does not require any pre-cached data. |

## How to Implement This in Your Application
### Database Table: pacs008
```sql
CREATE TABLE pacs008 (
  id          SERIAL PRIMARY KEY,
  EndToEndId  VARCHAR(35)  NOT NULL,
  document    JSONB, -- The full pacs.008 message
  TenantId    VARCHAR(50)  NOT NULL,
  -- A GIN index on the EndToEndId within the JSONB is recommended if this is a common query pattern
  INDEX idx_pacs008_doc_e2eid ON pacs008 USING GIN ((document -> 'FIToFICstmrCdtTrf' -> 'CdtTrfTxInf' -> 'PmtId' ->> 'EndToEndId'));
);
```
### Your Rule Config Structure
```json
{
  "config": {
    "bands": [
      { "subRuleRef": ".01", "lowerLimit": 0, "upperLimit": 18 },    // Minor
      { "subRuleRef": ".02", "lowerLimit": 80, "upperLimit": null }  // Elderly
    ]
  }
}
```
### Your determineOutcome Function
```javascript
function determineOutcome(debtorAge, ruleConfig, ruleResult) {
  const band = ruleConfig.config.bands.find(b =>
    debtorAge >= b.lowerLimit &&
    (b.upperLimit === null || debtorAge < b.upperLimit)
  );

  if (!band) {
    return { ...ruleResult, subRuleRef: '.00', reason: 'Debtor age is within normal range.' };
  }

  return {
    ...ruleResult,
    subRuleRef: band.subRuleRef,
    reason: `Debtor age of ${debtorAge} falls into a high-risk category.`
  };
}
```

### Data Flow Summary
```
Incoming pacs.002 transaction
        │
        ▼
Extract EndToEndId
        │
        ▼
Query `pacs008` table ──► Get the full JSON document for that EndToEndId
        │
        └── Extract Debtor's Date of Birth from the JSON
                                │
                                ▼
                         In code, calculate the debtor's age at the time of the transaction
                                │
                                ▼
                         Match the age against configured risk bands
                                │
                                ▼
                         Return risk subRuleRef (.01/.02)
```
### Key Insight
This rule leverages the rich KYC data embedded within ISO 20022 messages. By verifying demographic information like age against expected norms, it provides a fundamental layer of risk assessment. It can help flag transactions involving minors, who cannot legally enter into contracts, or the elderly, who can be targets of financial abuse, adding valuable context to a transaction's risk profile.

Each rule is documented with its business logic, detection algorithm, configurable parameters, required KYC and core banking data inputs, cache requirements, regulatory references, and alert output specifications. and also db design for that specific rule the fields and the variables configurations.
