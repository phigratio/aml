"use strict";
// SPDX-License-Identifier: Apache-2.0
Object.defineProperty(exports, "__esModule", { value: true });
exports.handleTransaction = exports.amountTracking = void 0;
const amountTracking = (tolerance, commission, targetAmount, historicalAmounts) => {
    let amountTracker = 0;
    for (let i = 0; i < historicalAmounts.length; i += 1) {
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
    if (!req.DataCache.cdtrAcctId || !req.DataCache.creDtTm) {
        throw new Error('DataCache object not retrievable');
    }
    const maxQueryRange = ruleConfig.config.parameters.maxQueryRange;
    const currentPacs002TimeFrame = req.transaction.FIToFIPmtSts.GrpHdr.CreDtTm;
    const creditorAccountId = req.DataCache.cdtrAcctId;
    const queryResult = await executeRemoteQuery(databaseManager, creditorAccountId, maxQueryRange, currentPacs002TimeFrame, req.DataCache.creDtTm, req.transaction.TenantId);
    if (!Array.isArray(queryResult) || !queryResult[0]?.historicalAmounts.length) {
        const reason = ruleConfig.config.exitConditions.find((exit) => exit.subRuleRef === '.x01')?.reason ??
            'Insufficient transaction history and no exit condition in config';
        return { ...ruleResult, subRuleRef: '.x01', reason };
    }
    const allNumbers = queryResult.every((entry) => Array.isArray(entry.historicalAmounts) && entry.historicalAmounts.every((amt) => typeof amt === 'number'));
    if (!allNumbers) {
        throw new Error('Data error: query result type mismatch - expected [numbers]');
    }
    const commission = ruleConfig.config.parameters.commission;
    const tolerance = ruleConfig.config.parameters.tolerance;
    const targetAmount = queryResult[0].targetAmount;
    const historicalAmounts = queryResult.map((entry) => entry.historicalAmounts).flat();
    const iterationValue = (0, exports.amountTracking)(tolerance, commission, Number(targetAmount), historicalAmounts);
    if (iterationValue === -1) {
        const reason = ruleConfig.config.exitConditions.find((exit) => exit.subRuleRef === '.x03')?.reason ??
            'No non-commissioned transaction mirroring detected and no exit condition in config';
        return { ...ruleResult, subRuleRef: '.x03', reason };
    }
    return determineOutcome(iterationValue, ruleConfig, ruleResult);
};
exports.handleTransaction = handleTransaction;
const executeRemoteQuery = async (databaseManager, creditorAccountId, maxQueryRange, currentPacs002TimeFrame, creDtTm, tenantId) => {
    const queryString = `
    WITH newest AS (
      SELECT
        EndToEndId,
        Amt,
        CreDtTm::timestamptz
      FROM
        transaction
      WHERE
        source = $1
        AND TxTp = 'pacs.008.001.10'
        AND CreDtTm::timestamptz < $2::timestamptz
        AND TenantId = $5
      ORDER BY
        CreDtTm::timestamptz DESC
      LIMIT
        1
    ), all_success AS (
      SELECT
        DISTINCT t.EndToEndId
      FROM
        transaction t
        JOIN newest n ON TRUE
      WHERE
        t.source = $1
        AND t.TxTp = 'pacs.002.001.12'
        AND t.TxSts = 'ACCC'
        AND t.CreDtTm::timestamptz < n.CreDtTm::timestamptz
        AND t.CreDtTm::timestamptz >= n.CreDtTm::timestamptz - ($3::bigint * interval '1 millisecond')
        AND t.CreDtTm::timestamptz <= $4::timestamptz
        AND t.TenantId = $5
    ),
    hist AS (
      SELECT
        t.Amt AS Amt,
        t.CreDtTm::timestamptz
      FROM
        transaction t
        JOIN all_success s USING (EndToEndId)
      WHERE
        t.TxTp = 'pacs.008.001.10' AND t.TenantId = $5
      ORDER BY
        t.CreDtTm::timestamptz DESC
    )
    SELECT
      (
        SELECT
          n.Amt
        FROM
          newest n
      ) AS "targetAmount",
      (
        SELECT
          COALESCE(
            array_agg(
              Amt
              ORDER BY
                CreDtTm::timestamptz DESC
            ),
            '{}'::numeric []
          )
        from
          hist
      ) AS "historicalAmounts";`;
    const queryResult = await databaseManager._eventHistory.query(queryString, [
        creditorAccountId,
        creDtTm,
        maxQueryRange,
        currentPacs002TimeFrame,
        tenantId,
    ]);
    return queryResult.rows;
};
//# sourceMappingURL=rule-026.js.map