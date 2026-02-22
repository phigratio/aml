package com.tms.aml.engine.rule.impl;

import com.tms.aml.domain.Transaction;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

final class TransactionMetadataUtil {

    private TransactionMetadataUtil() {
    }

    static Map<String, String> parsePurposeMetadata(Transaction transaction) {
        Map<String, String> result = new LinkedHashMap<>();
        String purpose = transaction.transactionPurpose();
        if (purpose == null || purpose.isBlank()) {
            return result;
        }

        String[] parts = purpose.split("[;|]");
        for (String part : parts) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            int idx = token.indexOf('=');
            if (idx <= 0 || idx >= token.length() - 1) {
                continue;
            }
            String key = token.substring(0, idx).trim().toLowerCase(Locale.ROOT);
            String value = token.substring(idx + 1).trim();
            if (!key.isEmpty() && !value.isEmpty()) {
                result.put(key, value);
            }
        }
        return result;
    }

    static Optional<GeoPoint> parseGeoPoint(Transaction transaction) {
        Map<String, String> metadata = parsePurposeMetadata(transaction);

        if (metadata.containsKey("geo")) {
            Optional<GeoPoint> parsed = parseLatLon(metadata.get("geo"));
            if (parsed.isPresent()) {
                return parsed;
            }
        }

        if (metadata.containsKey("lat") && metadata.containsKey("lon")) {
            try {
                return Optional.of(new GeoPoint(
                    Double.parseDouble(metadata.get("lat")),
                    Double.parseDouble(metadata.get("lon"))
                ));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }

        return Optional.empty();
    }

    static Optional<String> extractType(Transaction transaction) {
        Map<String, String> metadata = parsePurposeMetadata(transaction);
        if (metadata.containsKey("type")) {
            return Optional.of(metadata.get("type").toUpperCase(Locale.ROOT));
        }
        if (transaction.transactionPurpose() != null && !transaction.transactionPurpose().isBlank()) {
            return Optional.of(transaction.transactionPurpose().trim().toUpperCase(Locale.ROOT));
        }
        return Optional.empty();
    }

    static Optional<String> extractChannel(Transaction transaction) {
        Map<String, String> metadata = parsePurposeMetadata(transaction);
        if (metadata.containsKey("channel")) {
            return Optional.of(metadata.get("channel").toUpperCase(Locale.ROOT));
        }
        return Optional.empty();
    }

    static Optional<GeoPoint> parseLatLon(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String[] arr = value.split(",");
        if (arr.length != 2) {
            return Optional.empty();
        }
        try {
            return Optional.of(new GeoPoint(Double.parseDouble(arr[0].trim()), Double.parseDouble(arr[1].trim())));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    static double haversineKm(GeoPoint a, GeoPoint b) {
        final double earthRadiusKm = 6371.0088;
        double lat1 = Math.toRadians(a.latitude());
        double lon1 = Math.toRadians(a.longitude());
        double lat2 = Math.toRadians(b.latitude());
        double lon2 = Math.toRadians(b.longitude());

        double dLat = lat2 - lat1;
        double dLon = lon2 - lon1;

        double sinLat = Math.sin(dLat / 2.0);
        double sinLon = Math.sin(dLon / 2.0);

        double h = sinLat * sinLat + Math.cos(lat1) * Math.cos(lat2) * sinLon * sinLon;
        return 2.0 * earthRadiusKm * Math.asin(Math.sqrt(h));
    }

    record GeoPoint(double latitude, double longitude) {
    }
}
