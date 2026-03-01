"use strict";
// SPDX-License-Identifier: Apache-2.0
Object.defineProperty(exports, "__esModule", { value: true });
exports.handleTransaction = handleTransaction;
async function handleTransaction(req, determineOutcome, ruleRes, loggerService, ruleConfig, databaseManager) {
    // Guard statements to throw errors early
    // ruleConfig must be tenant-specific, retrieved using req.transaction.TenantId
    if (!ruleConfig.config.bands?.length) {
        throw new Error('Invalid config provided - bands not provided');
    }
    const endToEndId = req.transaction.FIToFIPmtSts.TxInfAndSts.OrgnlEndToEndId;
    const sql = `
    SELECT
      document -> 'FIToFICstmrCdtTrf' -> 'CdtTrfTxInf' -> 'Dbtr' -> 'Id' -> 'PrvtId' -> 'DtAndPlcOfBirth' ->> 'BirthDt' AS "BirthDt"
    FROM
      pacs008
    WHERE
      EndToEndId = $1
      AND TenantId = $2;`;
    const tenantId = req.transaction.TenantId;
    const dateOfBirthRes = await databaseManager._rawHistory.query(sql, [endToEndId, tenantId]);
    const dateOfBirthObj = dateOfBirthRes?.rows[0];
    // Validates only for YYYY-MM-DD format
    const validDateRegex = /^\d{4}-\d{2}-\d{2}$/;
    if (!dateOfBirthObj?.BirthDt || typeof dateOfBirthObj.BirthDt !== 'string' || !validDateRegex.test(dateOfBirthObj.BirthDt)) {
        throw new Error('Data error: query result type mismatch - expected date');
    }
    // Determine age of debtor relative to when transaction occurred
    const transactionDate = req.transaction.FIToFIPmtSts.GrpHdr.CreDtTm;
    const debtorAge = calculateAge(dateOfBirthObj.BirthDt, transactionDate);
    return determineOutcome(debtorAge, ruleConfig, ruleRes);
}
function calculateAge(dateOfBirth, relativeToDate) {
    const fromDate = new Date(relativeToDate);
    const birthDate = new Date(dateOfBirth);
    let age = fromDate.getFullYear() - birthDate.getFullYear();
    const months = fromDate.getMonth() - birthDate.getMonth();
    if (months < 0 || (months === 0 && fromDate.getDate() < birthDate.getDate())) {
        age -= 1;
    }
    return age;
}
//# sourceMappingURL=rule-028.js.map