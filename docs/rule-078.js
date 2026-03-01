"use strict";
// SPDX-License-Identifier: Apache-2.0
Object.defineProperty(exports, "__esModule", { value: true });
exports.handleTransaction = exports.exclusiveDetermineOutcome = void 0;
const exclusiveDetermineOutcome = (value, caseObj) => {
    const ruleResult = caseObj.expressions.find((expression) => expression?.value === value);
    return ruleResult ?? caseObj.alternative;
};
exports.exclusiveDetermineOutcome = exclusiveDetermineOutcome;
const handleTransaction = async (req, _determineOutcome, ruleResult, _loggerService, ruleConfig, databaseManager) => {
    if (!ruleConfig.config.cases?.expressions.length) {
        throw new Error('Invalid config provided - cases not provided');
    }
    const endToEndId = req.transaction.FIToFIPmtSts.TxInfAndSts.OrgnlEndToEndId;
    const tenantId = req.transaction.TenantId;
    let sql;
    if (!(process.env.QUOTING === 'true')) {
        sql = `
      SELECT
      document -> 'FIToFICstmrCdtTrf' -> 'CdtTrfTxInf' -> 'Purp' ->> 'Cd' AS "CtgyPurpPrtry"
      FROM
        pacs008
      WHERE
        EndToEndId = $1
        AND TenantId = $2;`;
    }
    else {
        sql = `
      SELECT
        document -> 'CstmrCdtTrfInitn' -> 'PmtInf' -> 'CdtTrfTxInf' -> 'PmtTpInf' -> 'CtgyPurp' ->> 'Prtry' AS "CtgyPurpPrtry"
      FROM
        pain001
      WHERE
        EndToEndId = $1
        AND TenantId = $2;`;
    }
    const result = await databaseManager._rawHistory.query(sql, [endToEndId, tenantId]);
    const unwrappedResult = result.rows[0].CtgyPurpPrtry;
    if (!unwrappedResult || typeof unwrappedResult !== 'string' || !unwrappedResult.trim().length) {
        throw new Error('Data error: query result type mismatch - expected string');
    }
    const outcome = (0, exports.exclusiveDetermineOutcome)(unwrappedResult, ruleConfig.config.cases);
    // cases have no numeric result. defaulting to 0
    ruleResult.indpdntVarbl = 0;
    return {
        ...ruleResult,
        reason: outcome.reason,
        subRuleRef: outcome.subRuleRef,
    };
};
exports.handleTransaction = handleTransaction;
//# sourceMappingURL=rule-078.js.map