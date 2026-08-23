package com.thang.chargeops.station.dto.station.response;

import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.station.dto.license.response.LicenseSummaryResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminStationDetailResponse(
        UUID id,
        String stationCode,
        String name,
        String description,
        String addressLine,
        String provinceName,
        String wardName,
        BigDecimal latitude,
        BigDecimal longitude,
        String contactPhone,
        int plannedChargePointCount,
        StationStatus status,
        Instant createdAt,
        UUID ownerId,
        String ownerDisplayName,
        String ownerEmail,
        String ownerPhoneNumber,
        List<StationAssetResponse> assets,
        List<StationOperatingPeriodResponse> operatingPeriods,
        LicenseSummaryResponse licenseSummary
) {
}
//bị thiếu sau T17 cần ít nhất mớ này
//chargePoints[]
//  id/code/name/zone
//  provisioningStatus
//  operationalStatus
//  maxPowerKw
//  connectors[]
//    id/code/type/power
//    runtimeStatus
//    qrAvailable