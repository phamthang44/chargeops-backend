package com.thang.chargeops.station.dto.station.response;

import com.thang.chargeops.common.enums.TouRatePeriodCode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record StationAvailabilityResponse(
        UUID stationId,
        UUID connectorId,
        LocalDate date,
        String timezone,
        Instant generatedAt,
        int minDurationMinutes,
        int durationStepMinutes,
        int maxDurationMinutes,
        List<TimeRangeResponse> operatingWindows,
        List<TimeRangeResponse> busyRanges,
        List<PriceRangeResponse> priceRanges
) {
    public record TimeRangeResponse(
            Instant startAt,
            Instant endAt
    ) {}

    public record PriceRangeResponse(
            Instant startAt,
            Instant endAt,
            BigDecimal rateVndPerKwh,
            TouRatePeriodCode periodCode
    ) {}
}
