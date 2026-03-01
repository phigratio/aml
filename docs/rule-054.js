"use strict";
// SPDX-License-Identifier: Apache-2.0
Object.defineProperty(exports, "__esModule", { value: true });
exports.handleTransaction = exports.calculateBenfordsLaw = void 0;
const calculateBenfordsLaw = (historicAmounts) => {
    let chiSquare = 0;
    const numAmounts = historicAmounts.length;
    const observed = [0, 0, 0, 0, 0, 0, 0, 0, 0];
    const BenfordsLawArray = [0.30103, 0.17609, 0.12494, 0.09691, 0.07918, 0.06695, 0.05799, 0.05115, 0.04576];
    const expected = BenfordsLawArray.map((value) => value * numAmounts);
    historicAmounts.forEach((number) => {
        observed[Number(String(number).substring(0, 1)) - 1]++;
    });
    for (let index = 0; index < expected.length; index++) {
        chiSquare += Math.pow(observed[index] - expected[index], 2) / expected[index];
    }
    return chiSquare;
};
exports.calculateBenfordsLaw = calculateBenfordsLaw;
const handleTransaction = async (req, determineOutcome, ruleResult, _loggerService, ruleConfig, databaseManager) => {
    const tenantId = req.transaction.TenantId;
    if (!ruleConfig.config.bands?.length) {
        throw new Error('Invalid config provided - bands not provided');
    }
    if (!ruleConfig.config.parameters) {
        throw new Error('Invalid config provided - parameters not provided');
    }
    if (!ruleConfig.config.parameters.minimumNumberOfTransactions ||
        typeof ruleConfig.config.parameters.minimumNumberOfTransactions !== 'number') {
        throw new Error('Invalid config provided - Minimum number of transactions parameter not provided or invalid type');
    }
    if (!ruleConfig.config.exitConditions?.length) {
        throw new Error('Invalid config provided - exitConditions not provided');
    }
    if (req.transaction.FIToFIPmtSts.TxInfAndSts.TxSts !== 'ACCC') {
        const UnsuccessfulTransaction = ruleConfig.config.exitConditions.find(({ subRuleRef }) => subRuleRef === '.x00');
        if (UnsuccessfulTransaction === undefined) {
            throw new Error('Unsuccessful transaction and no exit condition in config');
        }
        return {
            ...ruleResult,
            reason: UnsuccessfulTransaction.reason,
            subRuleRef: UnsuccessfulTransaction.subRuleRef,
        };
    }
    if (!req.DataCache.dbtrAcctId) {
        throw new Error('DataCache object not retrievable');
    }
    const debtorAccountId = req.DataCache.dbtrAcctId;
    const currentPacs002TimeFrame = req.transaction.FIToFIPmtSts.GrpHdr.CreDtTm;
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
        AND TenantId = $3
    )
    SELECT
      t.Amt AS "Amt"
    FROM
      transaction AS t
      JOIN all_success s USING (EndToEndId)
    WHERE
      t.TxTp = 'pacs.008.001.10'
      AND t.TenantId = $3;`;
    const historicalAmountsData = await databaseManager._eventHistory.query(queryString, [
        debtorAccountId,
        currentPacs002TimeFrame,
        tenantId,
    ]);
    if (!historicalAmountsData.rows.length) {
        throw new Error('Data error: irretrievable transaction history');
    }
    const historicalAmounts = historicalAmountsData.rows.map((r) => Number(r.Amt));
    if (historicalAmounts.length < ruleConfig.config.parameters.minimumNumberOfTransactions) {
        const reason = ruleConfig.config.exitConditions.find(({ subRuleRef }) => subRuleRef === '.x01')?.reason ??
            'At least 50 historical transactions required and the exit condition was not found in the config';
        return { ...ruleResult, subRuleRef: '.x01', reason };
    }
    const allNumbers = historicalAmounts.every((element) => !isNaN(element));
    if (!allNumbers) {
        throw new Error('Data error: query result type mismatch - expected [amounts]');
    }
    const chiSquareValue = (0, exports.calculateBenfordsLaw)(historicalAmounts);
    return determineOutcome(chiSquareValue, ruleConfig, ruleResult);
};
exports.handleTransaction = handleTransaction;
//# sourceMappingURL=rule-054.js.map