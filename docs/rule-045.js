"use strict";
// SPDX-License-Identifier: Apache-2.0
Object.defineProperty(exports, "__esModule", { value: true });
exports.handleTransaction = void 0;
const handleTransaction = async (req, determineOutcome, ruleResult, _loggerService, ruleConfig, databaseManager) => {
    if (!ruleConfig.config.bands?.length) {
        throw new Error('Invalid config provided - bands not provided');
    }
    if (!req.DataCache.cdtrAcctId) {
        throw new Error('DataCache object not retrievable');
    }
    const creditorAccountId = req.DataCache.cdtrAcctId;
    const currentPacs002TimeFrame = req.transaction.FIToFIPmtSts.GrpHdr.CreDtTm;
    const tenantId = req.transaction.TenantId;
    const queryString = `
    SELECT
      COUNT(*) AS "numberOfSuccessfulTransactions"
    FROM
      transaction
    WHERE
      source = $1
      AND TxTp = 'pacs.002.001.12'
      AND TxSts = 'ACCC'
      AND CreDtTm::timestamptz <= $2::timestamptz
      AND TenantId = $3;`;
    const numberOfSuccessfulTransactionsWrapped = await databaseManager._eventHistory.query(queryString, [creditorAccountId, currentPacs002TimeFrame, tenantId]);
    const numberOfSuccessfulTransactions = Number(numberOfSuccessfulTransactionsWrapped.rows[0]?.numberOfSuccessfulTransactions);
    if (isNaN(numberOfSuccessfulTransactions)) {
        throw new Error('Data error: query result type mismatch - expected number');
    }
    return determineOutcome(numberOfSuccessfulTransactions, ruleConfig, ruleResult);
};
exports.handleTransaction = handleTransaction;
//# sourceMappingURL=rule-045.js.map