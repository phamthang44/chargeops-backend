package com.thang.chargeops.booking.pricing;

import com.thang.chargeops.common.enums.TouRatePeriodCode;

import java.math.BigDecimal;
import java.time.Instant;

public record PriceSegment(
        Instant startAt,
        Instant endAt,
        int durationMin,
        String label,
        TouRatePeriodCode periodCode,
        BigDecimal rateVndPerKwh
) {

    public PriceSegment {
        if (startAt == null || endAt == null || !startAt.isBefore(endAt)) {
            throw new IllegalArgumentException("Price segment requires a valid time window");
        }
        if (durationMin <= 0) {
            throw new IllegalArgumentException("durationMin must be greater than zero");
        }
        if (periodCode == null) {
            throw new IllegalArgumentException("periodCode is required");
        }
        if (rateVndPerKwh == null || rateVndPerKwh.signum() < 0) {
            throw new IllegalArgumentException("rateVndPerKwh cannot be negative");
        }
        label = label == null || label.isBlank() ? periodCode.name() : label;
    }
}
