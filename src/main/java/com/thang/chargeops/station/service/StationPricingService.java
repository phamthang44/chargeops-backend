package com.thang.chargeops.station.service;

import com.thang.chargeops.station.service.model.StationPriceRange;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface StationPricingService {

    /**
     * Tính mức giá sạc/kWh có hiệu lực tại một thời điểm cho luồng Driver
     * và Booking Engine.
     */
    BigDecimal resolvePriceAt(UUID stationId, Instant targetTime);

    /**
     * Chia khoảng yêu cầu thành các đoạn giá, chọn cấu hình thương mại theo
     * phiên bản có hiệu lực tại từng thời điểm trong khoảng. effectiveAt được
     * giữ làm mốc fallback cho dữ liệu cũ chưa gắn với phiên bản lịch.
     */
    List<StationPriceRange> resolvePriceRanges(
            UUID stationId,
            Instant effectiveAt,
            Instant rangeStart,
            Instant rangeEnd
    );
}
