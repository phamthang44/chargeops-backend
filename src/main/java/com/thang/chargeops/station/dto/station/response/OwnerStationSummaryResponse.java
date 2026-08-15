package com.thang.chargeops.station.dto.station.response;

import com.thang.chargeops.common.enums.StationStatus;

import java.util.UUID;

public record OwnerStationSummaryResponse(
        UUID id,
        String stationCode,
        String name,
        String addressLine,
        String provinceName,
        String wardName,
        int plannedChargePointCount,
        StationStatus status,
        LicenseSummaryResponse licenseSummary
) {
}
