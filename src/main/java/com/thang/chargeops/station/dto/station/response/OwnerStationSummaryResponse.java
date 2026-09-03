package com.thang.chargeops.station.dto.station.response;

import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.common.enums.StationOperatingState;
import com.thang.chargeops.common.enums.StationOperationalStatus;
import com.thang.chargeops.station.dto.license.response.LicenseSummaryResponse;

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
        LicenseSummaryResponse licenseSummary,
        // ⬇️ CẦN BỔ SUNG 2 TRƯỜNG DƯỚI ĐÂY:
        int actualChargePointCount, // Tổng số trụ sạc thực tế đã kích hoạt (provisioning_status = 'ACTIVE')
        int onlineChargePointCount, // Số trụ sạc đang online/khả dụng tiếp nhận sạc
        StationOperationalStatus operationalStatus,
        String operationalStatusReason,
        boolean openNow,
        StationOperatingState operatingState,
        boolean scheduleConfigured
) {
}
