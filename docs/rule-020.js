"use strict";
// SPDX-License-Identifier: Apache-2.0
Object.defineProperty(exports, "__esModule", { value: true });
exports.handleTransaction = handleTransaction;
async function handleTransaction(req, determineOutcome, ruleRes, loggerService, ruleConfig, databaseManager) {
    const tenantId = req.transaction.TenantId;
    if (!ruleConfig.config.bands || ruleConfig.config.bands.length <= 0)
        throw new Error('Invalid config provided - bands not provided');
    if (!ruleConfig.config.exitConditions)
        throw new Error('Invalid config provided - exitConditions not provided');
    const InsufficientHistory = ruleConfig.config.exitConditions.find((b) => b.subRuleRef === '.x01');
    const UnsuccessfulTransaction = ruleConfig.config.exitConditions.find((b) => b.subRuleRef === '.x00');
    const NoVarianceIncrease = ruleConfig.config.exitConditions.find((b) => b.subRuleRef === '.x03');
    const NoVarianceEqualOrDecrease = ruleConfig.config.exitConditions.find((b) => b.subRuleRef === '.x04');
    if (req.transaction.FIToFIPmtSts.TxInfAndSts.TxSts !== 'ACCC') {
        if (!UnsuccessfulTransaction)
            throw new Error('Unsuccessful transaction and no exit condition in config');
        return { ...ruleRes, reason: UnsuccessfulTransaction.reason, subRuleRef: UnsuccessfulTransaction.subRuleRef };
    }
    const { CreDtTm: currentPacs002TimeFrame } = req.transaction.FIToFIPmtSts.GrpHdr;
    const { OrgnlEndToEndId: endToEndId } = req.transaction.FIToFIPmtSts.TxInfAndSts;
    const { cdtrAcctId } = req.DataCache;
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
        AND CreDtTm::timestamptz <= $2::timestamptz
        AND TenantId = $3
    )
    SELECT
      t.EndToEndId as "EndToEndId",
      t.CreDtTm as "CreDtTm",
      t.Amt as "Amount"
    FROM
      transaction AS t
      JOIN all_success s USING (EndToEndId)
    WHERE
      t.TxTp = 'pacs.008.001.10'
      AND t.TenantId = $3
    ORDER BY
      t.CreDtTm::timestamptz DESC;`;
    const queryResult = await databaseManager._eventHistory.query(queryString, [cdtrAcctId, currentPacs002TimeFrame, tenantId]);
    const e2eIndex = queryResult.rows?.findIndex((i) => i.EndToEndId === endToEndId);
    if (typeof e2eIndex !== 'number' || e2eIndex < 0 || queryResult.rows.length <= 1) {
        if (InsufficientHistory === undefined) {
            throw new Error('Insufficient History and no exit condition in config');
        }
        return {
            ...ruleRes,
            subRuleRef: InsufficientHistory.subRuleRef,
            reason: InsufficientHistory.reason,
        };
    }
    const transactions = queryResult.rows.map((row) => ({
        CreDtTm: row.CreDtTm,
        Amount: Number(row.Amount),
        EndToEndId: row.EndToEndId,
    }));
    if (!isSuccessSet(transactions)) {
        throw new Error('Data error: query result type mismatch - expected [{timestamps, amounts}]');
    }
    const [currentTransaction] = transactions.splice(e2eIndex, 1);
    const { avg, stdDev } = calcAvgAndStandardDev(transactions);
    /*
      If the standard deviation == 0 and the amount of the current transaction > the historical average amount, abort with exit condition .x03
    */
    // safe casting, we have already checked
    const currentAmount = currentTransaction.Amount;
    if (stdDev === 0 && currentAmount > avg) {
        if (NoVarianceIncrease === undefined) {
            throw new Error('No Variance with Increase and no exit condition in config');
        }
        return {
            ...ruleRes,
            subRuleRef: NoVarianceIncrease.subRuleRef,
            reason: NoVarianceIncrease.reason,
        };
    }
    /*
      If the standard deviation == 0 and the amount of the current transaction <= the historical average amount, abort with exit condition .x04
    */
    if (stdDev === 0 && currentAmount <= avg) {
        if (NoVarianceEqualOrDecrease === undefined) {
            throw new Error('No Variance with Equal or Decrease and no exit condition in config');
        }
        return {
            ...ruleRes,
            subRuleRef: NoVarianceEqualOrDecrease.subRuleRef,
            reason: NoVarianceEqualOrDecrease.reason,
        };
    }
    /*
      The requirements for this rule is to evaluate the amount of the latest transaction
      against the historical average plus a specific number of standard deviations.
      The rule config contains the standard deviation multipliers as the band limits for the evaluation
      and a straightforward evaluation of the amount against a band is not yet possible.
      This transformation is currently handled in the rule processor where the latest amount is
      evaluated against the average plus the limits multiplied by the standard deviation.
      Iterate through all the result bands in the config.bands[] array and replace the current band limits with:
      band limit * standard deviation + mean.
    */
    for (const currConfig of ruleConfig.config.bands) {
        if ('upperLimit' in currConfig && typeof currConfig.upperLimit === 'number') {
            currConfig.upperLimit = currConfig.upperLimit * stdDev + avg;
        }
        if ('lowerLimit' in currConfig && typeof currConfig.lowerLimit === 'number') {
            currConfig.lowerLimit = currConfig.lowerLimit * stdDev + avg;
        }
    }
    return determineOutcome(currentAmount, ruleConfig, ruleRes);
}
function isSuccessSet(arr) {
    return arr.every((el) => typeof el === 'object' && typeof el.CreDtTm === 'string' && typeof el.Amount === 'number' && typeof el.EndToEndId === 'string');
}
function calcAvgAndStandardDev(arr) {
    const n = arr.length;
    let sum = 0;
    let sumSquared = 0;
    arr.forEach((el) => {
        sum += el.Amount;
    });
    const avg = sum / n;
    arr.forEach((el) => {
        sumSquared += (el.Amount - avg) * (el.Amount - avg);
    });
    const variance = sumSquared / (n - 1);
    const stdDev = Math.sqrt(variance);
    return { avg, stdDev };
}
//# sourceMappingURL=rule-020.js.map