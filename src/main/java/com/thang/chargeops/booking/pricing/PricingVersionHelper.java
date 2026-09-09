package com.thang.chargeops.booking.pricing;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

public final class PricingVersionHelper {

    private PricingVersionHelper() {
    }

    public static String computePricingVersion(
            UUID connectorId,
            Instant startAt,
            Instant endAt,
            int durationMin,
            String currency,
            long totalAmount,
            PriceBasis basis,
            List<PriceLine> priceLines
    ) {
        String canonical = canonicalPayload(
                connectorId,
                startAt,
                endAt,
                durationMin,
                currency,
                totalAmount,
                basis,
                priceLines
        );
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "SHA-256 is required by the Java runtime",
                    e
            );
        }
    }

    private static String canonicalPayload(
            UUID connectorId,
            Instant startAt,
            Instant endAt,
            int durationMin,
            String currency,
            long totalAmount,
            PriceBasis basis,
            List<PriceLine> priceLines
    ) {
        StringBuilder payload = new StringBuilder();
        payload.append("{");
        appendField(payload, "connectorId", connectorId.toString()).append(",");
        appendField(payload, "startAt", startAt.toString()).append(",");
        appendField(payload, "endAt", endAt.toString()).append(",");
        appendField(payload, "durationMin", durationMin).append(",");
        appendField(payload, "currency", currency).append(",");
        appendField(payload, "totalAmount", totalAmount).append(",");
        payload.append("\"pricingBasis\":");
        appendBasis(payload, basis).append(",");
        payload.append("\"priceLines\":[");
        List<PriceLine> orderedLines = priceLines.stream()
                .sorted(Comparator.comparingLong(PriceLine::sequence))
                .toList();
        for (int index = 0; index < orderedLines.size(); index++) {
            if (index > 0) {
                payload.append(",");
            }
            appendLine(payload, orderedLines.get(index));
        }
        payload.append("]}");
        return payload.toString();
    }

    private static StringBuilder appendBasis(StringBuilder payload, PriceBasis basis) {
        payload.append("{");
        appendField(payload, "kind", basis.kind()).append(",");
        appendField(payload, "rateUnit", basis.rateUnit()).append(",");
        appendField(payload, "formulaVersion", basis.formulaVersion()).append(",");
        appendField(payload, "energyFactor", decimal(basis.energyFactor())).append(",");
        appendField(payload, "powerKw", decimal(basis.powerKw())).append(",");
        appendField(payload, "energyDecimalPlaces", basis.energyDecimalPlaces()).append(",");
        appendField(payload, "lineAmountRoundingVnd", basis.lineAmountRoundingVnd()).append(",");
        appendField(payload, "roundingMode", basis.roundingMode().name());
        payload.append("}");
        return payload;
    }

    private static StringBuilder appendLine(StringBuilder payload, PriceLine line) {
        payload.append("{");
        appendField(payload, "sequence", line.sequence()).append(",");
        appendField(payload, "startAt", line.startAt().toString()).append(",");
        appendField(payload, "endAt", line.endAt().toString()).append(",");
        appendField(payload, "durationMin", line.durationMin()).append(",");
        appendField(payload, "label", line.label()).append(",");
        appendField(payload, "periodCode", line.periodCode().name()).append(",");
        appendField(payload, "rateVndPerKwh", decimal(line.rateVndPerKwh())).append(",");
        appendField(payload, "estimatedEnergyKwh", decimal(line.estimatedEnergyKwh())).append(",");
        appendField(payload, "amount", line.amount());
        payload.append("}");
        return payload;
    }

    private static StringBuilder appendField(
            StringBuilder payload,
            String key,
            String value
    ) {
        return payload.append("\"")
                .append(key)
                .append("\":\"")
                .append(escape(value))
                .append("\"");
    }

    private static StringBuilder appendField(
            StringBuilder payload,
            String key,
            long value
    ) {
        return payload.append("\"")
                .append(key)
                .append("\":")
                .append(value);
    }

    private static String decimal(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}
