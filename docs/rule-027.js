"use strict";
// SPDX-License-Identifier: Apache-2.0
Object.defineProperty(exports, "__esModule", { value: true });
exports.handleTransaction = exports.amountTracking = void 0;
const amountTracking = (tolerance, commission, targetAmount, historicalAmounts) => {
    let amountTracker = 0;
    for (let i = 0; i < historicalAmounts.length; i++) {
        const amount = historicalAmounts[i];
        amountTracker += amount;
        const commissionAmount = targetAmount * commission;
        if (amountTracker > targetAmount + commissionAmount - commissionAmount * tolerance &&
            amountTracker < targetAmount + commissionAmount + commissionAmount * tolerance) {
            return i + 1;
        }
    }
    return -1;
};
exports.amountTracking = amountTracking;
const handleTransaction = async (req, determineOutcome, ruleResult, _loggerService, ruleConfig, databaseManager) => {
    if (!ruleConfig.config.bands?.length) {
        throw new Error('Invalid config provided - bands not provided');
    }
    if (!ruleConfig.config.parameters) {
        throw new Error('Invalid config provided - parameters not provided');
    }
    if (!ruleConfig.config.parameters.tolerance || typeof ruleConfig.config.parameters.tolerance !== 'number') {
        throw new Error('Invalid config provided - tolerance parameter not provided or invalid type');
    }
    if (!ruleConfig.config.parameters.commission || typeof ruleConfig.config.parameters.commission !== 'number') {
        throw new Error('Invalid config provided - commission parameter not provided or invalid type');
    }
    if (!ruleConfig.config.parameters.maxQueryRange || typeof ruleConfig.config.parameters.maxQueryRange !== 'number') {
        throw new Error('Invalid config provided - maxQueryRange parameter not provided or invalid type');
    }
    if (!ruleConfig.config.exitConditions?.length) {
        throw new Error('Invalid config provided - exitConditions not provided');
    }
    if (req.transaction.FIToFIPmtSts.TxInfAndSts.TxSts !== 'ACCC') {
        const UnsuccessfulTransaction = ruleConfig.config.exitConditions.find((b) => b.subRuleRef === '.x00');
        if (UnsuccessfulTransaction === undefined)
            throw new Error('Unsuccessful transaction and no exit condition in config');
        return {
            ...ruleResult,
            reason: UnsuccessfulTransaction.reason,
            subRuleRef: UnsuccessfulTransaction.subRuleRef,
        };
    }
    if (!req.DataCache.dbtrAcctId || !req.DataCache.creDtTm || !req.DataCache.instdAmt) {
        throw new Error('DataCache object not retrievable');
    }
    const maxQueryRange = ruleConfig.config.parameters.maxQueryRange;
    const debtorAccountId = req.DataCache.dbtrAcctId;
    const currentPacs002TimeFrame = req.transaction.FIToFIPmtSts.GrpHdr.CreDtTm;
    const qryResult = await executeRemoteQuery(databaseManager, debtorAccountId, maxQueryRange, currentPacs002TimeFrame, req.DataCache.creDtTm, req.transaction.TenantId);
    if (qryResult[0].historicalAmounts === null) {
        const reason = ruleConfig.config.exitConditions.find((exit) => exit.subRuleRef === '.x01')?.reason ??
            'Insufficient transaction history and no exit condition in config';
        return { ...ruleResult, subRuleRef: '.x01', reason };
    }
    const allNumbers = qryResult.every((entry) => Array.isArray(entry.historicalAmounts) && entry.historicalAmounts.every((amt) => typeof amt === 'number'));
    if (!allNumbers) {
        throw new Error('Data error: query result type mismatch - expected [numbers]');
    }
    const commission = ruleConfig.config.parameters.commission;
    const tolerance = ruleConfig.config.parameters.tolerance;
    const targetAmount = req.DataCache.instdAmt.amt;
    const historicalAmounts = qryResult.map((entry) => entry.historicalAmounts).flat();
    const iterationValue = (0, exports.amountTracking)(tolerance, commission, targetAmount, historicalAmounts);
    if (iterationValue === -1) {
        const reason = ruleConfig.config.exitConditions.find((exit) => exit.subRuleRef === '.x03')?.reason ??
            'No non-commissioned transaction mirroring detected and no exit condition in config';
        return { ...ruleResult, subRuleRef: '.x03', reason };
    }
    return determineOutcome(iterationValue, ruleConfig, ruleResult);
};
exports.handleTransaction = handleTransaction;
const executeRemoteQuery = async (databaseManager, debtorAccountId, maxQueryRange, currentPacs002TimeFrame, creDtTm, tenantId) => {
    const sql = `
    WITH all_success AS (
      SELECT
        DISTINCT EndToEndId
      FROM
        transaction
      WHERE
        source = $1
        AND TxTp = 'pacs.002.001.12'
        AND TxSts = 'ACCC'
        AND TenantId = $5
        AND CreDtTm::timestamptz BETWEEN $2::timestamptz - ($3::bigint * interval '1 millisecond')
        AND $2::timestamptz
    ),
    hist AS (
      SELECT
        t.Amt as amt,
        t.CreDtTm::timestamptz
      FROM
        transaction t
        JOIN all_success s USING (EndToEndId)
      WHERE
        t.TxTp = 'pacs.008.001.10'
        AND t.TenantId = $5
        AND t.CreDtTm::timestamptz <= $4::timestamptz
      ORDER BY
        t.CreDtTm::timestamptz DESC
    )
    SELECT
      array_agg(amt) AS "historicalAmounts"
    FROM
      hist;`;
    let recentSuccessfulTransactionsAndTargetAmount;
    try {
        recentSuccessfulTransactionsAndTargetAmount = await databaseManager._eventHistory.query(sql, [
            debtorAccountId,
            currentPacs002TimeFrame,
            maxQueryRange,
            creDtTm,
            tenantId,
        ]);
    }
    catch (err) {
        throw new Error(JSON.stringify(err));
    }
    return recentSuccessfulTransactionsAndTargetAmount.rows;
};
//# sourceMappingURL=rule-027.js.map