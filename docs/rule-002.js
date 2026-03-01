"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.handleTransaction = void 0;
const handleTransaction = async (req, determineOutcome, ruleRes, _loggerService, ruleConfig, databaseManager) => {
    if (!ruleConfig.config.parameters) {
        throw new Error('Invalid config provided - parameters not provided');
    }
    if (!ruleConfig.config.parameters.maxQueryRange) {
        throw new Error('Invalid config provided - maxQueryRange parameter not provided');
    }
    if (!ruleConfig.config.bands) {
        throw new Error('Invalid config provided - bands not provided');
    }
    if (!req.DataCache.dbtrAcctId) {
        throw new Error('Data Cache does not have required dbtrAcctId');
    }
    const debtorAccountId = req.DataCache.dbtrAcctId;
    const pacs002CreDtTm = req.transaction.FIToFIPmtSts.GrpHdr.CreDtTm;
    const { TenantId: tenantId } = req.transaction;
    const maxQueryRange = ruleConfig.config.parameters.maxQueryRange;
    const queryString = `
    SELECT
      COUNT(*)::bigint AS "length"
    FROM
      transaction
    WHERE
      source = $1
      AND TxTp = 'pacs.002.001.12'
      AND TxSts = 'ACCC'
      AND CreDtTm::timestamptz BETWEEN $2::timestamptz - (
        $3::bigint * interval '1 millisecond'
      )
      AND $2::timestamptz
      AND TenantId = $4;`;
    const queryResult = await databaseManager._eventHistory.query(queryString, [
        debtorAccountId,
        pacs002CreDtTm,
        maxQueryRange,
        tenantId,
    ]);
    const count = Number(queryResult.rows[0]?.length);
    if (!count && count !== 0) {
        // 0 is a legal value
        throw new Error('Data error: irretrievable transaction history');
    }
    return determineOutcome(count, ruleConfig, ruleRes);
};
exports.handleTransaction = handleTransaction;
//# sourceMappingURL=rule-002.js.map