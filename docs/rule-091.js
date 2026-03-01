"use strict";
// SPDX-License-Identifier: Apache-2.0
Object.defineProperty(exports, "__esModule", { value: true });
exports.handleTransaction = handleTransaction;
// eslint-disable-next-line @typescript-eslint/require-await -- no await function applicable
async function handleTransaction(req, determineOutcome, ruleRes, _loggerService, ruleConfig, _databaseManager) {
    // Guard statements to throw errors early
    if (!req.DataCache.instdAmt) {
        throw new Error('Expected instdAmt in data cache');
    }
    const amountRes = req.DataCache.instdAmt.amt;
    return determineOutcome(amountRes, ruleConfig, ruleRes);
}
//# sourceMappingURL=rule-091.js.map