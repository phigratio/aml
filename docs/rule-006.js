"use strict";
// SPDX-License-Identifier: Apache-2.0
Object.defineProperty(exports, "__esModule", { value: true });
exports.handleTransaction = exports.earlyExitReason = exports.countMatchingAmounts = void 0;
const countMatchingAmounts = (amounts, tolerance) => {
    const offset = amounts[0] * tolerance;
    const lowerBound = amounts[0] - offset;
    const upperBound = amounts[0] + offset;
    return amounts.filter((amount) => {
        const out = amount < upperBound && amount > lowerBound;
        return out;
    }).length;
};
exports.countMatchingAmounts = countMatchingAmounts;
const earlyExitReason = (subRuleRef, _loggerService, exitConditions) => {
    const reason = exitConditions.find((exit) => exit.subRuleRef === subRuleRef)?.reason ?? `Exit condition ${subRuleRef} was not found`;
    return reason;
};
exports.earlyExitReason = earlyExitReason;
const handleTransaction = async (req, determineOutcome, ruleRes, _loggerService, ruleConfig, databaseManager) => {
    if (!req.DataCache.dbtrAcctId) {
        throw new Error('Data Cache does not have required dbtrAcctId');
    }
    if (!ruleConfig.config.parameters) {
        throw new Error('Invalid config provided - parameters not provided');
    }
    if (!ruleConfig.config.parameters.tolerance || typeof ruleConfig.config.parameters.tolerance !== 'number') {
        throw new Error('Invalid config provided - tolerance parameter not provided or invalid type');
    }
    if (!ruleConfig.config.parameters.maxQueryLimit || typeof ruleConfig.config.parameters.maxQueryLimit !== 'number') {
        throw new Error('Invalid config provided - maxQueryLimit parameter not provided or invalid type');
    }
    if (!ruleConfig.config.exitConditions) {
        throw new Error('Invalid config provided - exitConditions not provided');
    }
    const maxQueryLimit = ruleConfig.config.parameters.maxQueryLimit;
    const { tolerance } = ruleConfig.config.parameters;
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
    const tenantId = req.transaction.TenantId;
    const queryString = `
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
        AND TenantId = $4
    )
    SELECT
      t.Amt AS "Amt"
    FROM
      transaction AS t
      JOIN all_success s USING (EndToEndId)
    WHERE
      t.TxTp = 'pacs.008.001.10'
      AND t.TenantId = $4
    ORDER BY
      t.CreDtTm::timestamptz DESC
    LIMIT
      $3::int;`;
    const queryResult = await databaseManager._eventHistory.query(queryString, [
        debtorAccount,
        currentPacs002TimeFrame,
        maxQueryLimit,
        tenantId,
    ]);
    if (!Array.isArray(queryResult.rows)) {
        throw new Error('Data error: irretrievable transaction history');
    }
    if (queryResult.rows.length < 2) {
        const subRuleRef = '.x01';
        const reason = (0, exports.earlyExitReason)(subRuleRef, _loggerService, ruleConfig.config.exitConditions);
        return { ...ruleRes, subRuleRef, reason };
    }
    const matchingAmounts = (0, exports.countMatchingAmounts)(queryResult.rows.map((r) => Number(r.Amt)), tolerance);
    return determineOutcome(matchingAmounts, ruleConfig, ruleRes);
};
exports.handleTransaction = handleTransaction;
//# sourceMappingURL=rule-006.js.map