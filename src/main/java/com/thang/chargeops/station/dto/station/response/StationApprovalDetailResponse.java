package com.thang.chargeops.station.dto.station.response;

import com.thang.chargeops.common.enums.StationStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record StationApprovalDetailResponse(
        UUID id,
        String stationCode,
        String name,
        String ownerDisplayName,
        String provinceName,
        String wardName,
        String addressLine,
        int plannedChargePointCount,
        StationStatus status,
        Instant submittedAt,
        boolean licenseSubmitted,
        List<StationAssetResponse> assets
) {
}