# Full Breakdown of Rule-078

## What This Rule Does
This is a non-numeric, mapping-based rule that evaluates the risk associated with a transaction's **Category Purpose Code**. The ISO 20022 standard allows payments to be categorized with specific codes (e.g., `SALA` for salary, `GDDS` for goods). This rule extracts this code from the raw payment message and uses a configured set of `cases` to map it directly to a risk outcome (`subRuleRef` and `reason`).

## SQL Query — Deep Dive
This rule has a dynamic query that can target one of two tables based on an environment variable.

### Parameters Passed In
| Parameter | Value | Purpose |
|---|---|---|
| $1 | `endToEndId` | The `EndToEndId` of the current transaction, used to find the original payment document. |
| $2 | `tenantId` | For multi-tenant data isolation. |

### Query Logic
The rule checks `process.env.QUOTING`.
**If `false` (default):**
```sql
SELECT document -> 'FIToFICstmrCdtTrf' -> 'CdtTrfTxInf' -> 'Purp' ->> 'Cd' AS "CtgyPurpPrtry"
FROM pacs008
WHERE EndToEndId = $1 AND TenantId = $2;
```
*   **Purpose**: To find the `pacs.008` document for the transaction and extract the Category Purpose Code (`Purp.Cd`).

**If `true`:**
```sql
SELECT document -> 'CstmrCdtTrfInitn' -> 'PmtInf' -> 'CdtTrfTxInf' -> 'PmtTpInf' -> 'CtgyPurp' ->> 'Prtry' AS "CtgyPurpPrtry"
FROM pain001
WHERE EndToEndId = $1 AND TenantId = $2;
```
*   **Purpose**: To find a `pain.001` (Customer Credit Transfer Initiation) document and extract a proprietary category purpose code from a different JSON path.

**Result**: A single text value, `CtgyPurpPrtry`, representing the purpose code of the transaction.

---
## Post-Query Logic
The core logic is in the `exclusiveDetermineOutcome` function.
1.  It receives the `CtgyPurpPrtry` string from the database.
2.  It looks through the `ruleConfig.config.cases.expressions` array.
3.  It finds the first expression where `expression.value` exactly matches the `CtgyPurpPrtry` code.
4.  It returns the corresponding outcome (`subRuleRef` and `reason`).
5.  If no match is found, it returns a default `alternative` outcome defined in the config.

---
## Variable Importance Map
```
CtgyPurpPrtry  ──► WHAT (The business purpose of the payment)
outcome        ──► COMPUTED RESULT (The risk outcome directly mapped from the code)
```

## How to Implement This in Your Application
### Database Tables: pacs008 / pain001
```sql
-- For pacs.008
CREATE TABLE pacs008 (
  id          SERIAL PRIMARY KEY,
  EndToEndId  VARCHAR(35)  NOT NULL,
  document    JSONB,
  TenantId    VARCHAR(50)  NOT NULL
);
-- For pain.001
CREATE TABLE pain001 (
  id          SERIAL PRIMARY KEY,
  EndToEndId  VARCHAR(35)  NOT NULL,
  document    JSONB,
  TenantId    VARCHAR(50)  NOT NULL
);
```
### Your Rule Config Structure
This rule uses a `cases` structure instead of `bands`.
```json
{
  "config": {
    "cases": {
      "expressions": [
        { "value": "SALA", "subRuleRef": ".03", "reason": "Salary payment - low risk" },
        { "value": "GDDS", "subRuleRef": ".02", "reason": "Payment for goods - standard risk" },
        { "value": "CASH", "subRuleRef": ".01", "reason": "Cash withdrawal - high risk" },
        { "value": "GAMB", "subRuleRef": ".01", "reason": "Gambling payment - high risk" }
      ],
      "alternative": {
        "subRuleRef": ".02",
        "reason": "Uncategorized or standard risk payment purpose"
      }
    }
  }
}
```
### Key Insight
This rule leverages the structured, data-rich nature of ISO 20022 messages to make direct, logic-based risk assessments. Instead of inferring risk from amounts or frequencies, it can directly act on the stated *purpose* of a payment. This allows for the creation of very specific and targeted business rules, such as flagging all payments related to gambling (`GAMB`) or treating all salary (`SALA`) payments as inherently low-risk. It's a key tool for applying precise business logic to the transaction flow.

Each rule is documented with its business logic, detection algorithm, configurable parameters, required KYC and core banking data inputs, cache requirements, regulatory references, and alert output specifications. and also db design for that specific rule the fields and the variables configurations.
