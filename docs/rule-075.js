"use strict";
// SPDX-License-Identifier: Apache-2.0
Object.defineProperty(exports, "__esModule", { value: true });
exports.handleTransaction = void 0;
exports.haversineDistance = haversineDistance;
const handleTransaction = async (req, determineOutcome, ruleRes, loggerService, ruleConfig, databaseManager) => {
    if (!ruleConfig.config.parameters) {
        throw new Error('Invalid config provided - parameters not provided');
    }
    if (!ruleConfig.config.exitConditions?.length) {
        throw new Error('Invalid config provided - exitConditions not provided');
    }
    if (req.DataCache.dbtrAcctId == null || !req.DataCache.creDtTm) {
        throw new Error('Data Cache does not have required dbtrAcctId or creDtTm');
    }
    if (!ruleConfig.config.parameters.maxRadius || typeof ruleConfig.config.parameters.maxRadius !== 'number') {
        throw new Error('Invalid config provided - maxRadius parameter not provided or invalid type');
    }
    const endToEndId = req.transaction.FIToFIPmtSts.TxInfAndSts.OrgnlEndToEndId;
    const { maxRadius } = ruleConfig.config.parameters;
    const tenantId = req.transaction.TenantId;
    const queryString = `
    SELECT
      EndToEndId AS "EndToEndId",
      (
        document -> 'FIToFICstmrCdtTrf' -> 'SplmtryData' -> 'Envlp' -> 'Doc' -> 'InitgPty' -> 'Glctn' ->> 'Lat'
      )::double precision AS "lat",
      (
        document -> 'FIToFICstmrCdtTrf' -> 'SplmtryData' -> 'Envlp' -> 'Doc' -> 'InitgPty' -> 'Glctn' ->> 'Long'
      )::double precision AS "lon"
    FROM
      pacs008
    WHERE
      document -> 'DataCache' ->> 'dbtrAcctId' = $1
      AND CreDtTm::timestamptz <= $2::timestamptz
      AND TenantId = $3;`;
    const locationsResp = await databaseManager._rawHistory.query(queryString, [
        req.DataCache.dbtrAcctId,
        req.DataCache.creDtTm,
        tenantId,
    ]);
    if (!locationsResp.rows.length) {
        throw new Error('Data error: no items were returned from the query');
    }
    const resp = locationsResp.rows;
    const InsufficientHistory = ruleConfig.config.exitConditions.find((b) => b.subRuleRef === '.x01');
    if (resp.length === 1) {
        // exit condition .x01
        if (InsufficientHistory === undefined)
            throw new Error('Insufficient transaction history and no exit condition in config');
        return {
            ...ruleRes,
            reason: InsufficientHistory.reason,
            subRuleRef: InsufficientHistory.subRuleRef,
        };
    }
    const containsInvalid = resp.find(({ EndToEndId, lat, lon }) => 
    // check if all fields in the object are valid
    EndToEndId == null || lat == null || lon == null);
    if (containsInvalid) {
        throw new Error('Data error: some fields return invalid data');
    }
    const matches = resp.filter((item) => item.EndToEndId === endToEndId).map((item) => ({ lat: item.lat, lon: item.lon }));
    const [target] = matches;
    const withinLocations = resp.filter(({ EndToEndId, lat, lon }) => {
        if (EndToEndId !== endToEndId) {
            const { lat: lat2, lon: lon2 } = target;
            // no null assert is safe since we already checked all are valid
            return haversineDistance(parseFloat(lat), parseFloat(lat2), parseFloat(lon), parseFloat(lon2)) <= maxRadius;
        }
        else {
            return false;
        }
    });
    return determineOutcome(withinLocations.length, ruleConfig, ruleRes);
};
exports.handleTransaction = handleTransaction;
function haversineDistance(lat1, lat2, long1, long2) {
    // theta = distance/radius
    // havTheta = sin(theta/2)^2
    // havTheta = sin(deltaLat/2)^2 + sin(deltaLong/2)^2 * cos(lat1) * cos(lat2)
    const radius = 6371;
    // Distance in radians
    const deltaLat = (Math.PI / 180) * (lat2 - lat1);
    const deltaLong = (Math.PI / 180) * (long2 - long1);
    lat1 = (Math.PI / 180) * lat1;
    lat2 = (Math.PI / 180) * lat2;
    const havTheta = Math.pow(Math.sin(deltaLat / 2), 2) + Math.pow(Math.sin(deltaLong / 2), 2) * Math.cos(lat1) * Math.cos(lat2);
    const theta = 2 * Math.asin(Math.sqrt(havTheta));
    const distance = theta * radius;
    return distance;
}
//# sourceMappingURL=rule-075.js.map