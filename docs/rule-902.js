"use strict";
// SPDX-License-Identifier: Apache-2.0
Object.defineProperty(exports, "__esModule", { value: true });
exports.handleTransaction = handleTransaction;
async function handleTransaction(req, determineOutcome, ruleRes, loggerService, ruleConfig, databaseManager) {
    const context = `Rule-${ruleConfig.id} handleTransaction()`;
    const msgId = req.transaction.FIToFIPmtSts.GrpHdr.MsgId;
    loggerService.trace('Start - handle transaction', context, msgId);
    // Throw errors early if something we know we need is not provided - Guard Pattern
    if (!ruleConfig.config.bands?.length) {
        throw new Error('Invalid ruleConfig provided - bands not provided or empty');
    }
    if (!ruleConfig.config.exitConditions)
        throw new Error('Invalid ruleConfig provided - exitConditions not provided');
    if (!ruleConfig.config.parameters)
        throw new Error('Invalid ruleConfig provided - parameters not provided');
    if (!ruleConfig.config.parameters.maxQueryRange)
        throw new Error('Invalid ruleConfig provided - maxQueryRange parameter not provided');
    if (!req.DataCache.dbtrAcctId)
        throw new Error('Data Cache does not have required dbtrAcctId');
    // Step 1: Early exit conditions
    loggerService.trace('Step 1 - Early exit conditions', context, msgId);
    const UnsuccessfulTransaction = ruleConfig.config.exitConditions.find((b) => b.subRuleRef === '.x00');
    if (req.transaction.FIToFIPmtSts.TxInfAndSts.TxSts !== 'ACCC') {
        if (UnsuccessfulTransaction === undefined)
            throw new Error('Unsuccessful transaction and no exit condition in ruleConfig');
        return {
            ...ruleRes,
            reason: UnsuccessfulTransaction.reason,
            subRuleRef: UnsuccessfulTransaction.subRuleRef,
        };
    }
    // Step 2: Query Setup
    loggerService.trace('Step 2 - Query setup', context, msgId);
    const currentPacs002TimeFrame = req.transaction.FIToFIPmtSts.GrpHdr.CreDtTm;
    const creditorAccountId = req.DataCache.cdtrAcctId;
    const maxQueryRange = ruleConfig.config.parameters.maxQueryRange;
    const tenantId = req.transaction.TenantId;
    const values = [creditorAccountId, currentPacs002TimeFrame, maxQueryRange, tenantId];
    const queryString = `SELECT COUNT(*)::int AS length
FROM transaction tr
WHERE tr.source = $1
AND tr."txtp" = 'pacs.002.001.12'
AND ($2::timestamptz - tr."credttm"::timestamptz) <= $3 * interval '1 millisecond'
AND tr.tenantId = $4;`;
    // Step 3: Query Execution
    loggerService.trace('Step 3 - Query execution', context, msgId);
    const res = await databaseManager._eventHistory.query(queryString, values);
    const [{ length }] = res.rows;
    // Step 4: Query post-processing
    loggerService.trace('Step 4 - Query post-processing', context, msgId);
    // Return control to the rule-executer for rule result calculation
    loggerService.trace('End - handle transaction', context, msgId);
    return determineOutcome(length, ruleConfig, ruleRes);
}
//# sourceMappingURL=rule-902.js.map