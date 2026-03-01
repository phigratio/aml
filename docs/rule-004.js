"use strict";
// SPDX-License-Identifier: Apache-2.0
Object.defineProperty(exports, "__esModule", { value: true });
exports.handleTransaction = void 0;
const handleTransaction = async (req, determineOutcome, ruleResult, _loggerService, ruleConfig, databaseManager) => {
    if (!req.DataCache.dbtrAcctId) {
        throw new Error('Data Cache does not have required dbtrAcctId');
    }
    if (!ruleConfig.config.exitConditions) {
        throw new Error('Invalid config provided - exitConditions not provided');
    }
    const debtorAccount = req.DataCache.dbtrAcctId;
    const currentPacs002TimeFrame = req.transaction.FIToFIPmtSts.GrpHdr.CreDtTm;
    const endToEndId = req.transaction.FIToFIPmtSts.TxInfAndSts.OrgnlEndToEndId;
    const tenantId = req.transaction.TenantId;
    const query = `
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
        CreDtTm::timestamptz ASC
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
          COALESCE(
            ns.max_sent::timestamptz,
            '-infinity'::timestamptz
          ),
          COALESCE(
            nr.max_recv::timestamptz,
            '-infinity'::timestamptz
          )
        ),
        '-infinity'::timestamptz
      ) AS "maxCreDtTm"
    FROM
      newest_sent ns
      CROSS JOIN newest_received nr;`;
    const queryResponse = await databaseManager._eventHistory.query(query, [
        debtorAccount,
        currentPacs002TimeFrame,
        endToEndId,
        tenantId,
    ]);
    const dateStr = queryResponse.rows[0]?.maxCreDtTm;
    if (!dateStr) {
        return {
            ...ruleResult,
            subRuleRef: '.x01',
            reason: 'No verifiable debtor account activity detected',
        };
    }
    const timeStamp = new Date(dateStr).getTime();
    if (isNaN(timeStamp)) {
        throw new Error('Data error: query result type mismatch - expected DATE_TIME');
    }
    const currentTime = Date.now();
    // get the time difference in epoch millis backwards from now()
    const timeDifferenceInMs = currentTime - timeStamp;
    // determine the outcome (i think)
    const outcome = determineOutcome(timeDifferenceInMs, ruleConfig, ruleResult);
    return outcome;
};
exports.handleTransaction = handleTransaction;
//# sourceMappingURL=rule-004.js.map