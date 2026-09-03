package com.thang.chargeops.station.service;

import com.thang.chargeops.station.dto.station.request.UpdateStationPricingRequest;
import com.thang.chargeops.station.dto.station.response.StationPricingResponse;
import com.thang.chargeops.station.dto.station.response.StationScheduleHistoryResponse;

import java.util.List;
import java.util.UUID;

/**
 * Quản lý nguyên khối cấu hình thương mại của trạm, gồm giá cơ sở,
 * thời lượng đặt chỗ, lịch hoạt động và biểu giá theo khung giờ.
 */
public interface StationConfigurationService {

    StationPricingResponse getConfiguration(UUID stationId);

    StationPricingResponse updateConfiguration(
            UUID stationId,
            UpdateStationPricingRequest request
    );

    List<StationScheduleHistoryResponse> getScheduleHistory(UUID stationId);
}
