"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.handleTransaction = void 0;
const handleTransaction = async (req, determineOutcome, ruleResult, _loggerService, ruleConfig, databaseManager) => {
    if (!ruleConfig.config.bands?.length) {
        throw new Error('Invalid config provided - bands not provided');
    }
    if (!ruleConfig.config.exitConditions) {
        throw new Error('Invalid config provided - exitConditions not provided');
    }
    if (!req.DataCache.cdtrAcctId) {
        throw new Error('DataCache object not retrievable');
    }
    const creditorAccountId = req.DataCache.cdtrAcctId;
    const { TenantId: tenantId } = req.transaction;
    const currentPacs002TimeFrame = req.transaction.FIToFIPmtSts.GrpHdr.CreDtTm;
    const queryString = `
    WITH oldest_sent AS (
      SELECT
        MIN(CreDtTm::timestamptz) AS min_sent
      FROM
        transaction
      WHERE
        source = $1
        AND TxTp = 'pacs.008.001.10'
        AND CreDtTm::timestamptz <= $2::timestamptz
        AND TenantId = $3
    ),
    earliest_success AS (
      SELECT
        EndToEndId
      FROM
        transaction
      WHERE
        source = $1
        AND TxTp = 'pacs.002.001.12'
        AND TxSts = 'ACCC'
        AND CreDtTm::timestamptz <= $2::timestamptz
        AND TenantId = $3
      ORDER BY
        CreDtTm::timestamptz ASC
      LIMIT
        1
    ), oldest_success_p008 AS (
      SELECT
        MIN(t.CreDtTm::timestamptz) AS min_recv
      FROM
        transaction t
        JOIN earliest_success s USING (EndToEndId)
      WHERE
        t.TxTp = 'pacs.008.001.10'
        AND t.TenantId = $3
    )
    SELECT
      NULLIF(
        LEAST(
          COALESCE(os.min_sent, 'infinity'::timestamptz),
          COALESCE(orx.min_recv, 'infinity'::timestamptz)
        ),
        'infinity'::timestamptz
      ) AS "oldestCreDtTm"
    FROM
      oldest_sent os
      CROSS JOIN oldest_success_p008 orx;`;
    const queryResult = await databaseManager._eventHistory.query(queryString, [
        creditorAccountId,
        currentPacs002TimeFrame,
        tenantId,
    ]);
    const timeStampOldestSuccessfulPacs008Edge = queryResult.rows[0]?.oldestCreDtTm;
    if (!timeStampOldestSuccessfulPacs008Edge) {
        const reason = ruleConfig.config.exitConditions.find((exit) => exit.subRuleRef === '.x01')?.reason ??
            'No verifiable creditor account activity detected and no exit condition in config';
        return { ...ruleResult, subRuleRef: '.x01', reason };
    }
    if (!new Date(timeStampOldestSuccessfulPacs008Edge).getTime()) {
        throw new Error('Data error: query result type mismatch - expected DATE_TIME');
    }
    const timeStamp = new Date(timeStampOldestSuccessfulPacs008Edge).getTime();
    const currentTime = new Date(currentPacs002TimeFrame).getTime();
    // get the time difference in epoch millis backwards from now()
    const timeDifferenceInMs = currentTime - timeStamp;
    return determineOutcome(timeDifferenceInMs, ruleConfig, ruleResult);
};
exports.handleTransaction = handleTransaction;
//# sourceMappingURL=rule-001.js.map