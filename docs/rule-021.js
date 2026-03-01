"use strict";
// SPDX-License-Identifier: Apache-2.0
Object.defineProperty(exports, "__esModule", { value: true });
exports.handleTransaction = handleTransaction;
async function handleTransaction(req, determineOutcome, ruleRes, loggerService, ruleConfig, databaseManager) {
    // Guard statements to throw errors early
    if (!ruleConfig.config.bands)
        throw new Error('Invalid config provided - bands not provided');
    if (!ruleConfig.config.exitConditions)
        throw new Error('Invalid config provided - exitConditions not provided');
    if (!ruleConfig.config.parameters || typeof ruleConfig.config.parameters.tolerance !== 'number') {
        throw new Error('Invalid config provided - tolerance parameter not provided or invalid type');
    }
    const InsufficientHistory = ruleConfig.config.exitConditions.find((b) => b.subRuleRef === '.x01');
    const UnsuccessfulTransaction = ruleConfig.config.exitConditions.find((b) => b.subRuleRef === '.x00');
    if (req.transaction.FIToFIPmtSts.TxInfAndSts.TxSts !== 'ACCC') {
        if (UnsuccessfulTransaction === undefined)
            throw new Error('Unsuccessful transaction and no exit condition in config');
        return {
            ...ruleRes,
            reason: UnsuccessfulTransaction.reason,
            subRuleRef: UnsuccessfulTransaction.subRuleRef,
        };
    }
    const currentPacs002TimeFrame = req.transaction.FIToFIPmtSts.GrpHdr.CreDtTm;
    const creditorAccount = req.DataCache.cdtrAcctId;
    const maxQueryRange = ruleConfig.config.parameters.maxQueryRange
        ? ruleConfig.config.parameters.maxQueryRange
        : undefined;
    const tenantId = req.transaction.TenantId;
    const getAmtNewestPacs008 = `
    WITH all_success AS (
      SELECT
        DISTINCT EndToEndId
      FROM
        transaction
      WHERE
        source = $1
        AND TxTp = 'pacs.002.001.12'
        AND TxSts = 'ACCC'
        AND CreDtTm::timestamptz <= $2::timestamptz
        AND TenantId = $4
        AND (
          $3::bigint IS NULL
          OR CreDtTm::timestamptz >= $2::timestamptz - ($3::bigint * interval '1 millisecond')
        )
    )
    SELECT
      t.Amt AS "Amt"
    FROM
      transaction t
      JOIN all_success s USING (EndToEndId)
    WHERE
      t.TxTp = 'pacs.008.001.10'
      AND t.TenantId = $4
    ORDER BY
      t.CreDtTm::timestamptz DESC;`;
    const queryResult = await databaseManager._eventHistory.query(getAmtNewestPacs008, [
        creditorAccount,
        currentPacs002TimeFrame,
        maxQueryRange,
        tenantId,
    ]);
    if (!queryResult.rows.length) {
        throw new Error('Data error: irretrievable transaction history');
    }
    const amounts = queryResult.rows.map((r) => Number(r.Amt));
    if (amounts.some((amt) => isNaN(amt))) {
        throw new Error('Data error: query result type mismatch - expected [numbers]');
    }
    if (amounts.length <= 1) {
        if (InsufficientHistory === undefined)
            throw new Error('Insufficient History and no exit condition in config');
        return {
            ...ruleRes,
            subRuleRef: InsufficientHistory.subRuleRef,
            reason: InsufficientHistory.reason,
        };
    }
    // Calculate if matching numbers is within tolerance of first (latest) value
    const tolerance = amounts[0] * ruleConfig.config.parameters.tolerance;
    const countOfMatchingAmounts = amounts.reduce((count, val) => {
        if (Math.abs(val - amounts[0]) <= tolerance) {
            return count + 1;
        }
        return count;
    }, 0);
    return determineOutcome(countOfMatchingAmounts, ruleConfig, ruleRes);
}
//# sourceMappingURL=rule-021.js.map