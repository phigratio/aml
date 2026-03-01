"use strict";
// SPDX-License-Identifier: Apache-2.0
Object.defineProperty(exports, "__esModule", { value: true });
exports.handleTransaction = handleTransaction;
async function handleTransaction(req, determineOutcome, ruleRes, _loggerService, ruleConfig, databaseManager) {
    // Guard statements to throw errors early
    if (!ruleConfig.config.bands?.length) {
        throw new Error('Invalid config provided - bands not provided');
    }
    if (!ruleConfig.config.exitConditions?.length) {
        throw new Error('Invalid config provided - exitConditions not provided');
    }
    if (typeof ruleConfig.config.parameters?.evaluationIntervalTime !== 'number') {
        throw new Error('Invalid config provided - parameters not provided');
    }
    const { evaluationIntervalTime } = ruleConfig.config.parameters;
    const insufficientHistory = ruleConfig.config.exitConditions.find(({ subRuleRef }) => subRuleRef === '.x01');
    const unsuccessfulTransaction = ruleConfig.config.exitConditions.find(({ subRuleRef }) => subRuleRef === '.x00');
    const noVarianceIncrease = ruleConfig.config.exitConditions.find(({ subRuleRef }) => subRuleRef === '.x03');
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
    const tenantId = req.transaction.TenantId;
    const endToEndId = req.transaction.FIToFIPmtSts.TxInfAndSts.OrgnlEndToEndId;
    const debtorAccountId = req.DataCache.dbtrAcctId;
    const currentPacs002TimeFrame = req.transaction.FIToFIPmtSts.GrpHdr.CreDtTm;
    const queryString = `
    SELECT
      EndToEndId AS "EndToEndId",
      CreDtTm AS "CreDtTm"
    FROM
      transaction
    WHERE
      destination = $1
      AND TxTp = 'pacs.002.001.12'
      AND TxSts = 'ACCC'
      AND CreDtTm::timestamptz <= $2::timestamptz
      AND TenantId = $3
    ORDER BY
      CreDtTm::timestamptz DESC;`;
    const queryResults = await databaseManager._eventHistory.query(queryString, [
        debtorAccountId,
        currentPacs002TimeFrame,
        tenantId,
    ]);
    const e2eIndex = queryResults?.rows.findIndex((i) => i.EndToEndId === endToEndId);
    if ((!e2eIndex && e2eIndex !== 0) || typeof e2eIndex !== 'number' || e2eIndex < 0 || queryResults.rows.length <= 1) {
        if (insufficientHistory === undefined) {
            throw new Error('Insufficient History and no exit condition in config');
        }
        return {
            ...ruleRes,
            subRuleRef: insufficientHistory.subRuleRef,
            reason: insufficientHistory.reason,
        };
    }
    const transactions = new Set(queryResults.rows);
    if (!isSuccessSet(transactions)) {
        throw new Error('Data error: query result type mismatch - expected [timestamps]');
    }
    const historgram = createHistogram(evaluationIntervalTime, transactions);
    // Remove most recent interval from as its excluded from standard deviation calculation
    const currentInterval = historgram.pop() ?? 0;
    // Get total count of successful transactions excluding most recent interval
    const total = transactions.size - currentInterval;
    const { avg, stdDev } = calcAvgAndStandardDev(historgram, total);
    /*
      If the standard deviation = 0 and the number of transactions in the most recent interval > the historical average number of transactions, abort with exit condition .x03
    */
    if (stdDev === 0 && currentInterval > avg) {
        if (noVarianceIncrease === undefined) {
            throw new Error('No Variance with Increase and no exit condition in config');
        }
        return {
            ...ruleRes,
            subRuleRef: noVarianceIncrease.subRuleRef,
            reason: noVarianceIncrease.reason,
        };
    }
    /*
      If the standard deviation = 0 and the number of transactions in the most recent interval > the historical average number of transactions, abort with exit condition .x04
    */
    if (stdDev === 0 && currentInterval <= avg) {
        if (noVarianceEqualOrDecrease === undefined) {
            throw new Error('No Variance with Equal or Decrease and no exit condition in config');
        }
        return {
            ...ruleRes,
            subRuleRef: noVarianceEqualOrDecrease.subRuleRef,
            reason: noVarianceEqualOrDecrease.reason,
        };
    }
    for (const band of ruleConfig.config.bands) {
        if ('upperLimit' in band && typeof band.upperLimit === 'number') {
            band.upperLimit = band.upperLimit * stdDev + avg;
        }
        if ('lowerLimit' in band && typeof band.lowerLimit === 'number') {
            band.lowerLimit = band.lowerLimit * stdDev + avg;
        }
    }
    return determineOutcome(currentInterval, ruleConfig, ruleRes);
}
function isSuccessSet(arr) {
    for (const el of arr) {
        if (typeof el !== 'object' ||
            !('CreDtTm' in el) ||
            !('EndToEndId' in el) ||
            typeof el.CreDtTm !== 'string' ||
            typeof el.EndToEndId !== 'string') {
            return false;
        }
    }
    return true;
}
function calcAvgAndStandardDev(arr, dataSetLen) {
    const n = arr.length;
    let sumSquared = 0;
    const avg = dataSetLen / n;
    arr.forEach((el) => {
        sumSquared += (el - avg) * (el - avg);
    });
    const variance = sumSquared ? sumSquared / (n - 1) : 0;
    const stdDev = Math.sqrt(variance);
    return { avg, stdDev };
}
function createHistogram(interval, dataSet) {
    if (interval <= 0)
        return [];
    const countsPerInterval = [];
    const creationDateTimePool = [];
    for (const val of dataSet.keys()) {
        creationDateTimePool.push(new Date(val.CreDtTm).getTime());
    }
    const maxDate = creationDateTimePool[0];
    const numOfDates = creationDateTimePool.length;
    let window = new Date(maxDate).getTime() - interval;
    for (let index = 0, currentInterval = 0, checkCount = 0; checkCount < numOfDates; window -= interval, index += 1) {
        countsPerInterval[index] = 0;
        for (; currentInterval < numOfDates; currentInterval += 1) {
            const currentDate = creationDateTimePool[currentInterval];
            if (currentDate < window) {
                break;
            }
            checkCount += 1;
            countsPerInterval[index] += 1;
        }
    }
    return countsPerInterval.reverse();
}
//# sourceMappingURL=rule-010.js.map