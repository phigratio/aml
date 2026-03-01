"use strict";
// SPDX-License-Identifier: Apache-2.0
Object.defineProperty(exports, "__esModule", { value: true });
exports.handleTransaction = handleTransaction;
const tslib_1 = require("tslib");
const levenshtein = tslib_1.__importStar(require("fast-levenshtein"));
async function handleTransaction(req, determineOutcome, ruleRes, loggerService, ruleConfig, databaseManager) {
    // Guard statements to throw errors early
    if (!ruleConfig.config.bands?.length) {
        throw new Error('Invalid config provided - bands not provided');
    }
    if (!ruleConfig.config.exitConditions?.length) {
        throw new Error('Invalid config provided - exitConditions not provided');
    }
    if (!req.DataCache.dbtrAcctId) {
        throw new Error('Data Cache does not have required dbtrAcctId');
    }
    const tenantId = req.transaction.TenantId;
    const InsufficientHistory = ruleConfig.config.exitConditions.find((b) => b.subRuleRef === '.x01');
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
    // Querying database relavent transaction pacs 008 with end to end id
    const debtorAccount = req.DataCache.dbtrAcctId;
    const currentPacs002TimeFrame = req.transaction.FIToFIPmtSts.GrpHdr.CreDtTm;
    const queryString = `
    SELECT
      EndToEndId AS "EndToEndId"
    FROM
      transaction
    WHERE
      destination = $1
      AND TxTp = 'pacs.002.001.12'
      AND TxSts = 'ACCC'
      AND CreDtTm::timestamptz <= $2::timestamptz
      AND TenantId = $3
    ORDER BY
      CreDtTm::timestamptz DESC
    LIMIT
      2;`;
    loggerService.trace('executing rule query', `${ruleConfig.id} - ${ruleConfig.cfg}`);
    const newestPacs008 = await databaseManager._eventHistory.query(queryString, [
        debtorAccount,
        currentPacs002TimeFrame,
        tenantId,
    ]);
    if (!Array.isArray(newestPacs008?.rows) || !(newestPacs008?.rows.length > 1)) {
        if (InsufficientHistory === undefined) {
            throw new Error('Insufficient History and no exit condition in config');
        }
        return {
            ...ruleRes,
            subRuleRef: InsufficientHistory.subRuleRef,
            reason: InsufficientHistory.reason,
        };
    }
    const endToEndId = [newestPacs008.rows[0].EndToEndId, newestPacs008.rows[1].EndToEndId];
    const queryString2 = `
    SELECT
      document -> 'FIToFICstmrCdtTrf' -> 'RmtInf' ->> 'Ustrd' AS "Ustrd"
    FROM
      pacs008
    WHERE
      (
        document -> 'FIToFICstmrCdtTrf' -> 'CdtTrfTxInf' -> 'PmtId' ->> 'EndToEndId'
      ) = ANY($1::text[])
      AND tenantId = $2;`;
    const fullPacs008QueryResult = await databaseManager._rawHistory.query(queryString2, [endToEndId, tenantId]);
    if (!Array.isArray(fullPacs008QueryResult?.rows) || !(fullPacs008QueryResult?.rows.length > 1)) {
        if (InsufficientHistory === undefined) {
            throw new Error('Insufficient History and no exit condition in config');
        }
        return {
            ...ruleRes,
            subRuleRef: InsufficientHistory.subRuleRef,
            reason: InsufficientHistory.reason,
        };
    }
    const levenshteinDistance = levenshtein.get(fullPacs008QueryResult.rows[0].Ustrd, fullPacs008QueryResult.rows[1].Ustrd);
    return determineOutcome(levenshteinDistance, ruleConfig, ruleRes);
}
//# sourceMappingURL=rule-007.js.map