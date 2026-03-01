"use strict";
// SPDX-License-Identifier: Apache-2.0
Object.defineProperty(exports, "__esModule", { value: true });
exports.handleTransaction = void 0;
const handleTransaction = async (req, determineOutcome, ruleResult, _loggerService, ruleConfig, databaseManager) => {
    const tenantId = req.transaction.TenantId;
    if (!ruleConfig.config.bands?.length)
        throw new Error('Invalid config provided - bands not provided');
    if (!req.DataCache.dbtrId)
        throw new Error('DataCache object not retrievable');
    const debtorId = req.DataCache.dbtrId;
    const currentPacs002TimeFrame = req.transaction.FIToFIPmtSts.GrpHdr.CreDtTm;
    const sql = `
    SELECT
      COUNT(*) AS "numberOfAccounts"
    FROM
      account_holder AS accounts
    WHERE
      accounts.source = $1
      AND accounts.CreDtTm::timestamptz <= $2
      AND accounts.TenantId = $3;`;
    const numberOfAccountsData = await databaseManager._eventHistory.query(sql, [
        debtorId,
        currentPacs002TimeFrame,
        tenantId,
    ]);
    const [{ numberOfAccounts }] = numberOfAccountsData.rows;
    const accounts = Number(numberOfAccounts);
    if (isNaN(accounts)) {
        throw new Error('Data error: query result type mismatch - expected [numbers]');
    }
    return determineOutcome(accounts, ruleConfig, ruleResult);
};
exports.handleTransaction = handleTransaction;
//# sourceMappingURL=rule-083.js.map