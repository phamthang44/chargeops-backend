package com.thang.chargeops.station.dto.station.response;

import java.time.Instant;
import java.util.UUID;

public record StationApprovalSummaryResponse(
        UUID id,
        String stationCode,
        String name,
        String ownerDisplayName,
        String provinceName,
        int plannedChargePointCount,
        Instant submittedAt
) {
}
