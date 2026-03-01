"use strict";
// SPDX-License-Identifier: Apache-2.0
Object.defineProperty(exports, "__esModule", { value: true });
exports.handleTransaction = handleTransaction;
async function handleTransaction(req, determineOutcome, ruleRes, loggerService, ruleConfig, databaseManager) {
    // Guard statements to throw errors early
    if (!ruleConfig.config.bands?.length) {
        throw new Error('Invalid config provided - bands not provided');
    }
    if (!ruleConfig.config.exitConditions?.length) {
        throw new Error('Invalid config provided - exitConditions not provided');
    }
    const insufficientHistory = ruleConfig.config.exitConditions.find(({ subRuleRef }) => subRuleRef === '.x01');
    const unsuccessfulTransaction = ruleConfig.config.exitConditions.find(({ subRuleRef }) => subRuleRef === '.x00');
    const noVarianceIncrease = ruleConfig.config.exitConditions.find((b) => b.subRuleRef === '.x03');
    const noVarianceEqualOrDecrease = ruleConfig.config.exitConditions.find(({ subRuleRef }) => subRuleRef === '.x04');
    if (req.transaction.FIToFIPmtSts.TxInfAndSts.TxSts !== 'ACCC') {
        if (unsuccessfulTransaction === undefined) {
            throw new Error('Unsuccessful transaction and no exit condition in config');
        }
        return {
            ...ruleRes,
            reason: unsuccessfulTransaction.reason,
            subRuleRef: unsuccessfulTransaction.subRuleRef,
        };
    }
    const endToEndId = req.transaction.FIToFIPmtSts.TxInfAndSts.OrgnlEndToEndId;
    const currentPacs002TimeFrame = req.transaction.FIToFIPmtSts.GrpHdr.CreDtTm;
    const debtorAccount = req.DataCache.dbtrAcctId;
    const successSets = await processQuery(databaseManager, currentPacs002TimeFrame, debtorAccount, req.transaction.TenantId);
    const e2eIndex = successSets.rows.findIndex((i) => i.EndToEndId === endToEndId);
    if (typeof e2eIndex !== 'number' || !successSets.rows?.[0] || successSets.rows.length <= 1 || e2eIndex < 0) {
        if (insufficientHistory === undefined) {
            throw new Error('Insufficient History and no exit condition in config');
        }
        return {
            ...ruleRes,
            subRuleRef: insufficientHistory.subRuleRef,
            reason: insufficientHistory.reason,
        };
    }
    if (!isSuccessSet(successSets.rows)) {
        throw new Error('Data error: query result type mismatch - expected [{timestamps, amounts}]');
    }
    const transactions = successSets.rows;
    const currentTransaction = transactions.splice(e2eIndex, 1)[0];
    const { avg, stdDev } = calcAvgAndStandardDev(transactions);
    /*
      If the standard deviation == 0 and the amount of the current transaction > the historical average amount, abort with exit condition .x03
    */
    if (stdDev === 0 && currentTransaction.Amount > avg) {
        return exitCondition3(noVarianceIncrease, ruleRes);
    }
    /*
      If the standard deviation == 0 and the amount of the current transaction <= the historical average amount, abort with exit condition .x04
    */
    if (stdDev === 0 && currentTransaction.Amount <= avg) {
        return exitCondition4(noVarianceEqualOrDecrease, ruleRes);
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
    for (const band of ruleConfig.config.bands) {
        if ('upperLimit' in band && typeof band.upperLimit === 'number') {
            band.upperLimit = band.upperLimit * stdDev + avg;
        }
        if ('lowerLimit' in band && typeof band.lowerLimit === 'number') {
            band.lowerLimit = band.lowerLimit * stdDev + avg;
        }
    }
    ruleRes = determineOutcome(currentTransaction.Amount, ruleConfig, ruleRes);
    return ruleRes;
}
function isSuccessSet(arr) {
    return !arr.some((el) => el === null ||
        typeof el !== 'object' ||
        !('CreDtTm' in el) ||
        !('Amount' in el) ||
        !('EndToEndId' in el) ||
        typeof el.CreDtTm !== 'string' ||
        typeof el.Amount !== 'number' ||
        typeof el.EndToEndId !== 'string');
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
async function processQuery(databaseManager, currentPacs002TimeFrame, debtorAccount, tenantId) {
    const getCreditorSuccessSets = `
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
        AND TenantId = $3
    )
    SELECT
      t.EndToEndId AS "EndToEndId",
      t.CreDtTm AS "CreDtTm",
      CAST (t.Amt AS DOUBLE PRECISION) AS "Amount"
    FROM
      transaction AS t
      JOIN all_success s USING (EndToEndId)
    WHERE
      t.TxTp = 'pacs.008.001.10'
      AND t.TenantId = $3;`;
    const successSets = await databaseManager._eventHistory.query(getCreditorSuccessSets, [
        debtorAccount,
        currentPacs002TimeFrame,
        tenantId,
    ]);
    return successSets;
}
function exitCondition3(noVarianceIncrease, ruleRes) {
    if (noVarianceIncrease === undefined) {
        throw new Error('No Variance with Increase and no exit condition in config');
    }
    return {
        ...ruleRes,
        subRuleRef: noVarianceIncrease.subRuleRef,
        reason: noVarianceIncrease.reason,
    };
}
function exitCondition4(noVarianceEqualOrDecrease, ruleRes) {
    if (noVarianceEqualOrDecrease === undefined) {
        throw new Error('No Variance with Equal or Decrease and no exit condition in config');
    }
    return {
        ...ruleRes,
        subRuleRef: noVarianceEqualOrDecrease.subRuleRef,
        reason: noVarianceEqualOrDecrease.reason,
    };
}
//# sourceMappingURL=rule-048.js.map