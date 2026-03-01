"use strict";
// SPDX-License-Identifier: Apache-2.0
Object.defineProperty(exports, "__esModule", { value: true });
exports.handleTransaction = exports.amountTracking = void 0;
const amountTracking = (tolerance, targetAmount, historicalAmounts) => {
    let amountTracker = 0;
    const offsetTolerance = targetAmount * tolerance;
    for (let i = 0; i < historicalAmounts.length; i += 1) {
        const amount = historicalAmounts[i];
        amountTracker += amount;
        if (amountTracker > targetAmount - offsetTolerance && amountTracker < targetAmount + offsetTolerance) {
            return i + 1;
        }
    }
    return -1;
};
exports.amountTracking = amountTracking;
const handleTransaction = async (req, determineOutcome, ruleResult, _loggerService, ruleConfig, databaseManager) => {
    if (!ruleConfig.config.bands?.length)
        throw new Error('Invalid config provided - bands not provided');
    if (!ruleConfig.config.parameters)
        throw new Error('Invalid config provided - parameters not provided');
    if (!ruleConfig.config.parameters.tolerance || typeof ruleConfig.config.parameters.tolerance !== 'number') {
        throw new Error('Invalid config provided - tolerance parameter not provided or invalid type');
    }
    if (!ruleConfig.config.parameters.maxQueryRange || typeof ruleConfig.config.parameters.maxQueryRange !== 'number') {
        throw new Error('Invalid config provided - maxQueryRange parameter not provided or invalid type');
    }
    if (!ruleConfig.config.exitConditions?.length)
        throw new Error('Invalid config provided - exitConditions not provided');
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
    if (!req.DataCache.dbtrAcctId)
        throw new Error('DataCache object not retrievable');
    if (!req.DataCache.instdAmt?.amt)
        throw new Error('DataCache amount not retrievable');
    if (!req.DataCache.creDtTm)
        throw new Error('DataCache CreDtTm not retrievable');
    const debtorAccountId = req.DataCache.dbtrAcctId;
    const maxQueryRange = ruleConfig.config.parameters.maxQueryRange;
    const currentPacs002TimeFrame = req.transaction.FIToFIPmtSts.GrpHdr.CreDtTm;
    const tenantId = req.transaction.TenantId;
    const queryString = `
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
    )
    SELECT
      COALESCE(
        array_agg(
          t.Amt
          ORDER BY
            t.CreDtTm::timestamptz DESC
        ),
        '{}'::numeric []
      ) AS "historicalAmounts"
    FROM
      transaction t
      JOIN all_success s USING (EndToEndId)
    WHERE
      t.TxTp = 'pacs.008.001.10'
      AND t.TenantId = $5
      AND t.CreDtTm::timestamptz <= $4::timestamptz;`;
    const queryResult = await databaseManager._eventHistory.query(queryString, [
        debtorAccountId,
        currentPacs002TimeFrame,
        maxQueryRange,
        req.DataCache.creDtTm,
        tenantId,
    ]);
    const unWrappedResult = {
        historicalAmounts: queryResult.rows.map((row) => row.historicalAmounts.flat().map((value) => Number(value))).flat(),
    };
    if (!unWrappedResult?.historicalAmounts[0]) {
        const reason = ruleConfig.config.exitConditions.find((exit) => exit.subRuleRef === '.x01')?.reason ??
            'Insufficient transaction history and no exit condition in config';
        return { ...ruleResult, subRuleRef: '.x01', reason };
    }
    const allNumbers = unWrappedResult.historicalAmounts.every((element) => !isNaN(element));
    if (!allNumbers) {
        throw new Error('Data error: query result type mismatch - expected [numbers]');
    }
    const tolerance = ruleConfig.config.parameters.tolerance;
    const { amt: targetAmount } = req.DataCache.instdAmt;
    const { historicalAmounts } = unWrappedResult;
    const iterationValue = (0, exports.amountTracking)(tolerance, targetAmount, historicalAmounts);
    if (iterationValue === -1) {
        const reason = ruleConfig.config.exitConditions.find((exit) => exit.subRuleRef === '.x03')?.reason ??
            'No non-commissioned transaction mirroring detected and no exit condition in config';
        return { ...ruleResult, subRuleRef: '.x03', reason };
    }
    return determineOutcome(iterationValue, ruleConfig, ruleResult);
};
exports.handleTransaction = handleTransaction;
//# sourceMappingURL=rule-025.js.map