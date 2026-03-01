"use strict";
// SPDX-License-Identifier: Apache-2.0
Object.defineProperty(exports, "__esModule", { value: true });
exports.handleTransaction = void 0;
const handleTransaction = async (req, determineOutcome, ruleRes, _loggerService, ruleConfig, databaseManager) => {
    if (!ruleConfig.config.bands?.length) {
        throw new Error('Invalid config provided - bands not provided');
    }
    if (!ruleConfig.config.exitConditions?.length) {
        throw new Error('Invalid config provided - exitConditions not provided');
    }
    if (!ruleConfig.config.parameters) {
        throw new Error('Invalid config provided - parameters not provided');
    }
    if (!ruleConfig.config.parameters.maxQueryRange) {
        throw new Error('Invalid config provided - maxQueryRange parameter not provided');
    }
    if (!req.DataCache.dbtrAcctId) {
        throw new Error('Data Cache does not have required dbtrAcctId');
    }
    if (!req.DataCache.instdAmt) {
        throw new Error('Data Cache does not have required amt');
    }
    const UnsuccessfulTransaction = ruleConfig.config.exitConditions.find((b) => b.subRuleRef === '.x00');
    if (req.transaction.FIToFIPmtSts.TxInfAndSts.TxSts !== 'ACCC') {
        if (UnsuccessfulTransaction === undefined) {
            throw new Error('Unsuccessful transaction and no exit condition in config');
        }
        return {
            ...ruleRes,
            reason: UnsuccessfulTransaction.reason,
            subRuleRef: UnsuccessfulTransaction.subRuleRef,
        };
    }
    const endToEndId = req.transaction.FIToFIPmtSts.TxInfAndSts.OrgnlEndToEndId;
    const currentPacs002TimeFrame = req.transaction.FIToFIPmtSts.GrpHdr.CreDtTm;
    const maxQueryRange = ruleConfig.config.parameters.maxQueryRange;
    // Querying database relavent transaction pacs 008 with end to end id
    const debtorAccount = req.DataCache.dbtrAcctId;
    const tenantId = req.transaction.TenantId;
    const queryStr = `
    WITH all_success AS (
      SELECT
        DISTINCT EndToEndId
      FROM
        transaction
      WHERE
        destination = $1
        AND TxTp = 'pacs.002.001.12'
        AND TxSts = 'ACCC'
        AND CreDtTm::timestamptz <= $2::timestamptz
        AND CreDtTm::timestamptz >= $2::timestamptz - ($3::bigint * interval '1 millisecond')
        AND EndToEndId <> $4
        AND TenantId = $5
    )
    SELECT
      MAX(t.Amt) AS "highestAmount"
    FROM
      transaction t
      JOIN all_success s USING (EndToEndId)
    WHERE
      t.TxTp = 'pacs.008.001.10'
      AND t.TenantId = $5;`;
    const queryResult = await databaseManager._eventHistory.query(queryStr, [
        debtorAccount,
        currentPacs002TimeFrame,
        maxQueryRange,
        endToEndId,
        tenantId,
    ]);
    const [{ highestAmount: amount }] = queryResult.rows;
    const InsufficientHistory = ruleConfig.config.exitConditions.find((b) => b.subRuleRef === '.x01');
    if (amount == null) {
        if (InsufficientHistory === undefined) {
            throw new Error('Insufficient History and no exit condition in config');
        }
        return {
            ...ruleRes,
            subRuleRef: InsufficientHistory.subRuleRef,
            reason: InsufficientHistory.reason,
        };
    }
    const ratio = req.DataCache.instdAmt.amt / amount;
    return determineOutcome(ratio, ruleConfig, ruleRes);
};
exports.handleTransaction = handleTransaction;
//# sourceMappingURL=rule-018.js.map