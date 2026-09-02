package com.thang.chargeops.station.service;

import com.thang.chargeops.station.dto.station.response.StationDiscoveryDetailResponse;

import java.util.UUID;

/**
 * Read use case dành riêng cho trang chi tiết trạm công khai của Driver.
 */
public interface StationDetailService {

    StationDiscoveryDetailResponse getStationDetail(UUID stationId);
}
