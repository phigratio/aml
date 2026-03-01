"use strict";
// SPDX-License-Identifier: Apache-2.0
Object.defineProperty(exports, "__esModule", { value: true });
exports.handleTransaction = void 0;
const handleTransaction = async (req, determineOutcome, ruleResult, loggerService, ruleConfig, databaseManager) => {
    const tenantId = req.transaction.TenantId;
    // Validate config before any DB queries
    if (!ruleConfig.config?.bands?.length) {
        throw new Error('Invalid config provided - bands not provided');
    }
    if (!ruleConfig.config.exitConditions?.length) {
        throw new Error('Invalid config provided - exitConditions not provided');
    }
    if (req.transaction.FIToFIPmtSts.TxInfAndSts.TxSts !== 'ACCC') {
        const { exitConditions } = ruleConfig.config;
        const UnsuccessfulTransaction = exitConditions.find((b) => b.subRuleRef === '.x00');
        if (UnsuccessfulTransaction === undefined) {
            throw new Error('Unsuccessful transaction and no exit condition in config');
        }
        return {
            ...ruleResult,
            reason: UnsuccessfulTransaction.reason,
            subRuleRef: UnsuccessfulTransaction.subRuleRef,
        };
    }
    if (!req.DataCache.cdtrAcctId || !req.DataCache.dbtrAcctId) {
        throw new Error('DataCache object not retrievable');
    }
    const currentPacs002TimeFrame = req.transaction.FIToFIPmtSts.GrpHdr.CreDtTm;
    const creditorAccountId = req.DataCache.cdtrAcctId;
    const debtorAccountId = req.DataCache.dbtrAcctId;
    const queryString = `
    SELECT
      COUNT(*) AS "numberOfSuccessfulTransactions"
    FROM
      transaction
    WHERE
      source = $1
      AND destination = $2
      AND TxTp = 'pacs.002.001.12'
      AND TxSts = 'ACCC'
      AND CreDtTm::timestamptz <= $3::timestamptz
      AND TenantId = $4;`;
    const numberOfSuccessfulTransactionsData = await databaseManager._eventHistory.query(queryString, [creditorAccountId, debtorAccountId, currentPacs002TimeFrame, tenantId]);
    const numberOfSuccessfulTransactions = Number(numberOfSuccessfulTransactionsData.rows[0].numberOfSuccessfulTransactions);
    if (!numberOfSuccessfulTransactions) {
        throw new Error('Data error: irretrievable successful transaction from creditor and debtor accounts');
    }
    return determineOutcome(numberOfSuccessfulTransactions, ruleConfig, ruleResult);
};
exports.handleTransaction = handleTransaction;
//# sourceMappingURL=rule-030.js.map