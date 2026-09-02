package com.thang.chargeops.station.service;

import com.thang.chargeops.station.dto.station.request.UpdateStationPricingRequest;
import com.thang.chargeops.station.dto.station.response.StationPricingResponse;
import com.thang.chargeops.station.service.model.StationPriceRange;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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

    /**
     * Chia một khoảng thời gian thành các đoạn giá liên tục theo cấu hình
     * pricing có hiệu lực tại effectiveAt.
     */
    List<StationPriceRange> resolvePriceRanges(
            UUID stationId,
            Instant effectiveAt,
            Instant rangeStart,
            Instant rangeEnd
    );
}
