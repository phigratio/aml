"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.handleTransaction = handleTransaction;
async function handleTransaction(req, determineOutcome, ruleRes, _loggerService, ruleConfig, databaseManager) {
    const tenantId = req.transaction.TenantId;
    if (!ruleConfig.config.bands?.length)
        throw new Error('Invalid config provided - bands not provided');
    if (!ruleConfig.config.parameters?.maxQueryRangeUpstream || !ruleConfig.config.parameters.maxQueryRangeDownstream) {
        throw new Error('Invalid config provided - all parameters not provided');
    }
    const debtorAccount = req.DataCache.dbtrAcctId;
    const currentPacs002TimeFrame = req.transaction.FIToFIPmtSts.GrpHdr.CreDtTm;
    const debtorAccountId = debtorAccount;
    const maxQueryRangeUpstream = Number(ruleConfig.config.parameters.maxQueryRangeUpstream);
    const maxQueryRangeDownstream = Number(ruleConfig.config.parameters.maxQueryRangeDownstream);
    const getHighestCountSQL = `
    WITH upstreamDebtorAccounts AS (
      SELECT
        e.destination AS debtor
      FROM
        transaction e
      WHERE
        e.source = $1
        AND e.TxTp = 'pacs.002.001.12'
        AND e.CreDtTm::timestamptz >= (
          $2::timestamptz - ($3::bigint * INTERVAL '1 millisecond')
        )
        AND e.CreDtTm::timestamptz <= $2::timestamptz
        AND e.TenantId = $5
      GROUP BY
        e.destination
    ),
    uniquePairs AS (
      SELECT
        DISTINCT u.debtor,
        e.source AS creditor
      FROM
        upstreamDebtorAccounts u
        JOIN transaction e ON e.destination = u.debtor
      WHERE
        e.TxTp = 'pacs.002.001.12'
        AND e.CreDtTm::timestamptz >= (
          $2::timestamptz - ($4::bigint * INTERVAL '1 millisecond')
        )
        AND e.CreDtTm::timestamptz <= $2::timestamptz
        AND e.TenantId = $5
    ),
    highestCount AS (
      SELECT
        debtor,
        COUNT(DISTINCT creditor) AS creditors
      FROM
        uniquePairs
      GROUP BY
        debtor
      ORDER BY
        creditors DESC
      LIMIT
        1
    )
    SELECT
      COALESCE(MAX(creditors), 0) AS value
    FROM
      highestCount;`;
    const highestCountRows = await databaseManager._eventHistory.query(getHighestCountSQL, [
        debtorAccountId,
        currentPacs002TimeFrame,
        maxQueryRangeUpstream,
        maxQueryRangeDownstream,
        tenantId,
    ]);
    ruleRes = determineOutcome(highestCountRows.rows[0].value, ruleConfig, ruleRes);
    return ruleRes;
}
//# sourceMappingURL=rule-090.js.map