package com.thang.chargeops.station.service.model;

import com.thang.chargeops.common.enums.TouRatePeriodCode;

import java.math.BigDecimal;
import java.time.Instant;

public record StationPriceRange(
        Instant startAt,
        Instant endAt,
        BigDecimal rateVndPerKwh,
        TouRatePeriodCode periodCode
) {
}
