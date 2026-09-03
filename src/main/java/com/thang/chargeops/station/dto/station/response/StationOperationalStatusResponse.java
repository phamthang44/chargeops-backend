package com.thang.chargeops.station.dto.station.response;

import com.thang.chargeops.common.enums.StationOperationalStatus;

import java.util.UUID;

public record StationOperationalStatusResponse(
        UUID stationId,
        StationOperationalStatus operationalStatus,
        String reason
) {
}
