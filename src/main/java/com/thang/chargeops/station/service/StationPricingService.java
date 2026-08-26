package com.thang.chargeops.station.service;

import com.thang.chargeops.station.dto.station.request.UpdateStationPricingRequest;
import com.thang.chargeops.station.dto.station.response.StationPricingResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public interface StationPricingService {

    /**
     * Phục vụ Action 1: Lấy toàn bộ cấu hình Giá, Giờ hoạt động & TOU rates hiện tại của trạm.
     */
    StationPricingResponse getStationPricing(UUID stationId);

    /**
     * Phục vụ Action 5: Cập nhật nguyên khối (Atomic) toàn bộ cấu hình Giá, Giờ mở cửa & TOU rates.
     * Áp dụng Snapshot Temporal Versioning (đóng bản ghi cũ effectiveTo = now, tạo bản ghi mới effectiveFrom = now).
     */
    StationPricingResponse updateStationPricing(UUID stationId, UpdateStationPricingRequest request);

    /**
     * Phục vụ Action 6 (Driver / Booking Engine): Tính toán mức giá sạc / kWh có hiệu lực tại thời điểm cụ thể.
     */
    BigDecimal resolvePriceAt(UUID stationId, Instant targetTime);
}
