package com.thang.chargeops.booking.pricing;

import com.thang.chargeops.common.enums.TouRatePeriodCode;

import java.math.BigDecimal;
import java.time.Instant;

public record PriceLine(
        long sequence,
        Instant startAt,
        Instant endAt,
        int durationMin,
        String label,
        TouRatePeriodCode periodCode,
        BigDecimal rateVndPerKwh,
        BigDecimal estimatedEnergyKwh,
        long amount
) {
}
