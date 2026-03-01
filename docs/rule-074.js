"use strict";
// SPDX-License-Identifier: Apache-2.0
Object.defineProperty(exports, "__esModule", { value: true });
exports.handleTransaction = exports.haversineDistance = void 0;
const toRadians = (deg) => (Math.PI / 180) * deg;
const haversineDistance = (lat1, lat2, long1, long2) => {
    const radius = 6371;
    // Distance in radians
    const deltaLat = toRadians(lat2 - lat1);
    const deltaLong = toRadians(long2 - long1);
    const lat1Rad = toRadians(lat1);
    const lat2Rad = toRadians(lat2);
    const havTheta = Math.pow(Math.sin(deltaLat / 2), 2) + Math.pow(Math.sin(deltaLong / 2), 2) * Math.cos(lat1Rad) * Math.cos(lat2Rad);
    const theta = 2 * Math.asin(Math.sqrt(havTheta));
    const distance = theta * radius;
    return distance;
};
exports.haversineDistance = haversineDistance;
exports.default = exports.haversineDistance;
const handleTransaction = async (req, determineOutcome, ruleResult, _loggerService, ruleConfig, databaseManager) => {
    if (!ruleConfig.config.bands?.length) {
        throw new Error('Invalid config provided - bands not provided');
    }
    if (!ruleConfig.config.exitConditions?.length) {
        throw new Error('Invalid config provided - exitConditions not provided');
    }
    if (!ruleConfig.config.parameters) {
        throw new Error('Invalid config provided - parameters not provided');
    }
    if (!ruleConfig.config.parameters.maxQueryRange || typeof ruleConfig.config.parameters.maxQueryRange !== 'number') {
        throw new Error('Invalid config provided - maxQueryRange parameter not provided or invalid type');
    }
    const { OrgnlEndToEndId: endToEndId } = req.transaction.FIToFIPmtSts.TxInfAndSts;
    const { dbtrAcctId } = req.DataCache;
    const { maxQueryRange } = ruleConfig.config.parameters;
    const tenantId = req.transaction.TenantId;
    const queryString = `
    WITH assoc AS (
      SELECT
        CreDtTm::timestamptz AS assocCreDtTm
      FROM
        pacs008
      WHERE
        EndToEndId = $1
        AND TenantId = $4
      ORDER BY
        assocCreDtTm DESC
      LIMIT
        1
    )
    SELECT
      CreDtTm::timestamptz AS "CreDtTm",
      (
        t.document -> 'FIToFICstmrCdtTrf' -> 'SplmtryData' -> 'Envlp' -> 'Doc' -> 'InitgPty' -> 'Glctn' ->> 'Lat'
      )::double precision AS "lat",
      (
        t.document -> 'FIToFICstmrCdtTrf' -> 'SplmtryData' -> 'Envlp' -> 'Doc' -> 'InitgPty' -> 'Glctn' ->> 'Long'
      )::double precision AS "long"
    FROM
      pacs008 AS t
      JOIN assoc ON TRUE
    WHERE
      document -> 'DataCache' ->> 'dbtrAcctId' = $2
      AND TenantId = $4
      AND CreDtTm::timestamptz BETWEEN assoc.assocCreDtTm - ($3::bigint * interval '1 millisecond')
      AND assoc.assocCreDtTm
    ORDER BY
      CreDtTm::timestamptz DESC
    LIMIT
      2;`;
    const locationsResponse = await databaseManager._rawHistory.query(queryString, [
        endToEndId,
        dbtrAcctId,
        maxQueryRange,
        tenantId,
    ]);
    if (!locationsResponse.rows.length) {
        throw new Error('Data error: irretrievable transaction history');
    }
    const locationTimestamp = locationsResponse.rows;
    if (locationTimestamp.length === 1) {
        const InsufficientHistory = ruleConfig.config.exitConditions.find((b) => b.subRuleRef === '.x01');
        if (InsufficientHistory === undefined)
            throw new Error('Insufficient transaction history and no exit condition in config');
        return {
            ...ruleResult,
            reason: InsufficientHistory.reason,
            subRuleRef: InsufficientHistory.subRuleRef,
        };
    }
    const valueTypeChecks = locationTimestamp.every((locationTimestampEach) => {
        if (typeof new Date(locationTimestampEach.CreDtTm).getTime() !== 'number' ||
            isNaN(Number(locationTimestampEach.lat)) ||
            isNaN(Number(locationTimestampEach.long))) {
            return false;
        }
        return true;
    });
    if (!valueTypeChecks)
        throw new Error('Data error: query result type mismatch - expected [{timestamp, number, number}]');
    const distance = (0, exports.haversineDistance)(Number(locationTimestamp[0].lat), Number(locationTimestamp[1].lat), Number(locationTimestamp[0].long), Number(locationTimestamp[1].long));
    const correspondingTimestampMs = new Date(locationTimestamp[1].CreDtTm).getTime();
    const mostRecentTimestampMs = new Date(locationTimestamp[0].CreDtTm).getTime();
    const seconds = mostRecentTimestampMs - correspondingTimestampMs;
    const hours = seconds / 3600000;
    const velocity = distance / hours;
    return determineOutcome(velocity, ruleConfig, ruleResult);
};
exports.handleTransaction = handleTransaction;
//# sourceMappingURL=rule-074.js.map