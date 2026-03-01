"use strict";
// SPDX-License-Identifier: Apache-2.0
Object.defineProperty(exports, "__esModule", { value: true });
exports.handleTransaction = void 0;
const handleTransaction = async (req, determineOutcome, ruleResult, loggerService, ruleConfig, databaseManager) => {
    const tenantId = req.transaction.TenantId;
    if (!ruleConfig.config.bands?.length) {
        throw new Error('Invalid config provided - bands not provided');
    }
    if (!ruleConfig.config.exitConditions?.length) {
        throw new Error('Invalid config provided - exitConditions not provided');
    }
    if (!req.DataCache.dbtrAcctId) {
        throw new Error('DataCache object not retrievable');
    }
    const maxQueryLimit = ruleConfig.config.parameters?.maxQueryLimit;
    // Multi-tenant: Add tenantId filter to query
    if (req.transaction.FIToFIPmtSts.TxInfAndSts.TxSts !== 'ACCC') {
        const UnsuccessfulTransaction = ruleConfig.config.exitConditions?.find((b) => b.subRuleRef === '.x00');
        if (UnsuccessfulTransaction === undefined) {
            throw new Error('Unsuccessful transaction and no exit condition in config');
        }
        return {
            ...ruleResult,
            reason: UnsuccessfulTransaction.reason,
            subRuleRef: UnsuccessfulTransaction.subRuleRef,
        };
    }
    const debtorAccountId = req.DataCache.dbtrAcctId;
    const currentPacs002TimeFrame = req.transaction.FIToFIPmtSts.GrpHdr.CreDtTm;
    const query = {
        text: `SELECT
        t.source
      FROM
        transaction AS t
      WHERE
        t.destination = $1
        AND t.TxTp = 'pacs.002.001.12'
        AND t.TxSts = 'ACCC'
        AND t.CreDtTm::timestamptz <= $2::timestamptz
        AND t.TenantId = $3
      ORDER BY
        t.CreDtTm::timestamptz DESC`,
        values: [debtorAccountId, currentPacs002TimeFrame, tenantId],
    };
    if (maxQueryLimit !== undefined) {
        query.text += ' LIMIT $4';
        query.values.push(maxQueryLimit);
    }
    const newestPacs008Data = await databaseManager._eventHistory.query(query);
    if (!Array.isArray(newestPacs008Data.rows)) {
        throw new Error('Data error: irretrievable transaction history');
    }
    const newestPacs008 = newestPacs008Data.rows.map((val) => val.source);
    if (newestPacs008.length > (maxQueryLimit ?? 3) || newestPacs008.length < 2) {
        const InsufficientHistory = ruleConfig.config.exitConditions.find((b) => b.subRuleRef === '.x01');
        if (InsufficientHistory === undefined) {
            throw new Error('Insufficient History and no exit condition in config');
        }
        return {
            ...ruleResult,
            reason: InsufficientHistory.reason,
            subRuleRef: InsufficientHistory.subRuleRef,
        };
    }
    const [mostRecentCreditorId] = newestPacs008;
    const countOfMatchingCreditors = newestPacs008.reduce((accumulator, current) => (current === mostRecentCreditorId ? accumulator + 1 : accumulator), 0);
    return determineOutcome(countOfMatchingCreditors, ruleConfig, ruleResult);
};
exports.handleTransaction = handleTransaction;
//# sourceMappingURL=rule-008.js.map