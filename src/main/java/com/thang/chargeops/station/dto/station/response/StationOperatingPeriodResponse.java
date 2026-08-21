package com.thang.chargeops.station.dto.station.response;

import com.thang.chargeops.common.enums.StationDayOfWeek;

import java.time.LocalTime;
import java.util.UUID;

public record StationOperatingPeriodResponse(
        UUID id,
        StationDayOfWeek dayOfWeek,
        LocalTime openTime,
        LocalTime closeTime
) {
}
