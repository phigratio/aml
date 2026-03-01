"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.handleTransaction = void 0;
const handleTransaction = async (req, determineOutcome, ruleResult, _loggerService, ruleConfig, databaseManager) => {
    if (!ruleConfig.config.bands) {
        throw new Error('Invalid config provided - bands not provided');
    }
    if (!ruleConfig.config.exitConditions) {
        throw new Error('Invalid config provided - exitConditions not provided');
    }
    if (!req.DataCache.cdtrAcctId) {
        throw new Error('DataCache object not retrievable');
    }
    const creditorAccountId = req.DataCache.cdtrAcctId;
    const endToEndId = req.transaction.FIToFIPmtSts.TxInfAndSts.OrgnlEndToEndId;
    const currentPacs002TimeFrame = req.transaction.FIToFIPmtSts.GrpHdr.CreDtTm;
    const tenantId = req.transaction.TenantId;
    const queryString = `
    WITH newest_sent AS (
      SELECT
        MAX(CreDtTm::timestamptz) AS max_sent
      FROM
        transaction
      WHERE
        source = $1
        AND TxTp = 'pacs.008.001.10'
        AND EndToEndId <> $3
        AND CreDtTm::timestamptz < $2::timestamptz
        AND TenantId = $4
    ),
    all_success AS (
      SELECT
        EndToEndId
      FROM
        transaction
      WHERE
        source = $1
        AND TxTp = 'pacs.002.001.12'
        AND TxSts = 'ACCC'
        AND EndToEndId <> $3
        AND CreDtTm::timestamptz < $2::timestamptz
        AND TenantId = $4
      ORDER BY
        CreDtTm::timestamptz DESC
      LIMIT
        1
    ), newest_received AS (
      SELECT
        MAX(t.CreDtTm::timestamptz) AS max_recv
      FROM
        transaction t
        JOIN all_success s USING (EndToEndId)
      WHERE
        t.TxTp = 'pacs.008.001.10'
        AND t.TenantId = $4
    )
    SELECT
      NULLIF(
        GREATEST(
          COALESCE(ns.max_sent, '-infinity'::timestamptz),
          COALESCE(nr.max_recv, '-infinity'::timestamptz)
        ),
        '-infinity'::timestamptz
      ) AS "maxCreDtTm"
    FROM
      newest_sent ns
      CROSS JOIN newest_received nr;`;
    const queryResult = await databaseManager._eventHistory.query(queryString, [
        creditorAccountId,
        currentPacs002TimeFrame,
        endToEndId,
        tenantId,
    ]);
    const timeStampOldestSuccessfulPacs008Edge = queryResult.rows[0]?.maxCreDtTm;
    if (!timeStampOldestSuccessfulPacs008Edge) {
        const reason = ruleConfig.config.exitConditions.find((exit) => exit.subRuleRef === '.x01')?.reason ??
            'No verifiable creditor account activity detected and no exit condition in config';
        return { ...ruleResult, subRuleRef: '.x01', reason };
    }
    if (!new Date(timeStampOldestSuccessfulPacs008Edge).getTime()) {
        throw new Error('Data error: query result type mismatch - expected DATE_TIME');
    }
    const timeStamp = new Date(timeStampOldestSuccessfulPacs008Edge).getTime();
    const currentTime = Date.now();
    const timeDifferenceInMs = currentTime - timeStamp;
    return determineOutcome(timeDifferenceInMs, ruleConfig, ruleResult);
};
exports.handleTransaction = handleTransaction;
//# sourceMappingURL=rule-003.js.map