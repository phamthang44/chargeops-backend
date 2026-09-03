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
