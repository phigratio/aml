"use strict";
// SPDX-License-Identifier: Apache-2.0
Object.defineProperty(exports, "__esModule", { value: true });
exports.handleTransaction = void 0;
const handleTransaction = async (req, determineOutcome, ruleRes, _loggerService, ruleConfig, databaseManager) => {
    if (!ruleConfig.config.exitConditions?.length) {
        throw new Error('Invalid config provided - exitConditions not provided');
    }
    if (!req.DataCache.dbtrAcctId || !req.DataCache.creDtTm) {
        throw new Error('Data Cache does not have required dbtrAcctId or creDtTm');
    }
    const debtorAccountId = req.DataCache.dbtrAcctId;
    const tenantId = req.transaction.TenantId;
    const sql = `
    SELECT
      CreDtTm::timestamptz AS "CreDtTm"
    FROM
      transaction
    WHERE
      TxTp = 'pacs.008.001.10'
      AND source = $1
      AND CreDtTm::timestamptz <= $2::timestamptz
      AND TenantId = $3
    ORDER BY
      creDtTm::timestamptz DESC
    LIMIT
      2;`;
    const dbResponse = await databaseManager._eventHistory.query(sql, [
        debtorAccountId,
        req.DataCache.creDtTm,
        tenantId,
    ]);
    if (!dbResponse.rows.length) {
        throw new Error('Data error: query result type mismatch - expected [timestamp]');
    }
    const rows = dbResponse.rows;
    const InsufficientHistory = ruleConfig.config.exitConditions.find((b) => b.subRuleRef === '.x01');
    if (rows.length === 1) {
        // exit condition .x01
        if (InsufficientHistory === undefined) {
            throw new Error('Insufficient transaction history and no exit condition in config');
        }
        return {
            ...ruleRes,
            reason: InsufficientHistory.reason,
            subRuleRef: InsufficientHistory.subRuleRef,
        };
    }
    const newer = new Date(rows[0].CreDtTm).getTime();
    const older = new Date(rows[1].CreDtTm).getTime();
    // get the time difference in epoch millis backwards from now()
    const timeDifferenceInMs = newer - older;
    return determineOutcome(timeDifferenceInMs, ruleConfig, ruleRes);
};
exports.handleTransaction = handleTransaction;
//# sourceMappingURL=rule-076.js.map