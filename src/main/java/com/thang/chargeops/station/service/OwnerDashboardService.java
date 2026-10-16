package com.thang.chargeops.station.service;

import com.thang.chargeops.station.dto.dashboard.OwnerDashboardActivityResponse;
import com.thang.chargeops.station.dto.dashboard.OwnerDashboardAttentionPageResponse;
import com.thang.chargeops.station.dto.dashboard.OwnerDashboardStationPageResponse;
import com.thang.chargeops.station.dto.station.response.OwnerOperationsSummaryResponse;

import java.util.UUID;

public interface OwnerDashboardService {
    OwnerOperationsSummaryResponse summary();

    OwnerDashboardActivityResponse activity(UUID stationId, String period);

    OwnerDashboardStationPageResponse stations(UUID stationId, String period, int page, int size);

    OwnerDashboardAttentionPageResponse attention(UUID stationId, String kind, int page, int size);
}
