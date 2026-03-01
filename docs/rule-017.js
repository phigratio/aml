"use strict";
// SPDX-License-Identifier: Apache-2.0
Object.defineProperty(exports, "__esModule", { value: true });
exports.handleTransaction = void 0;
const handleTransaction = async (req, determineOutcome, ruleRes, _loggerService, ruleConfig, databaseManager) => {
    if (!ruleConfig.config.bands?.length) {
        throw new Error('Invalid config provided - bands not provided or empty');
    }
    if (!ruleConfig.config.parameters)
        throw new Error('Invalid config provided - parameters not provided');
    if (!ruleConfig.config.parameters.maxQueryRange)
        throw new Error('Invalid config provided - maxQueryRange parameter not provided');
    if (!req.DataCache.dbtrAcctId)
        throw new Error('Data Cache does not have required dbtrAcctId');
    const currentPacs002TimeFrame = req.transaction.FIToFIPmtSts.GrpHdr.CreDtTm;
    const debtorAccountId = req.DataCache.dbtrAcctId;
    const maxQueryRange = ruleConfig.config.parameters.maxQueryRange;
    const tenantId = req.transaction.TenantId;
    const queryString = `
    SELECT
      COUNT(*) AS "length"
    FROM
      transaction
    WHERE
      destination = $1
      AND TxTp = 'pacs.002.001.12'
      AND TxSts = 'ACCC'
      AND TenantID = $4
      AND 
        CreDtTm::timestamptz
            BETWEEN 
                $2::timestamptz - ($3::bigint * interval '1 millisecond')
            AND $2::timestamptz;`;
    const { rows: [{ length }], } = await databaseManager._eventHistory.query(queryString, [
        debtorAccountId,
        currentPacs002TimeFrame,
        maxQueryRange,
        tenantId,
    ]);
    const count = Number(length);
    if (isNaN(count)) {
        throw new Error('Data error: query result type mismatch - expected a number');
    }
    return determineOutcome(count, ruleConfig, ruleRes);
};
exports.handleTransaction = handleTransaction;
//# sourceMappingURL=rule-017.js.map