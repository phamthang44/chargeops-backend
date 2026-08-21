package com.thang.chargeops.station.dto.station.response;

import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.station.dto.license.response.LicenseSummaryResponse;

import java.time.Instant;
import java.util.UUID;

public record AdminStationListItemResponse(
        UUID id,
        String stationCode,
        String name,
        String addressLine,
        String provinceName,
        String wardName,
        UUID ownerId,
        String ownerDisplayName,
        String ownerEmail,
        String contactPhone,
        int plannedChargePointCount,
        StationStatus status,
        Instant createdAt,
        LicenseSummaryResponse licenseSummary
) {
}
