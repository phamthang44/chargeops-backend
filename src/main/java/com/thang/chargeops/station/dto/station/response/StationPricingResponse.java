package com.thang.chargeops.station.dto.station.response;

import com.thang.chargeops.common.enums.StationDayOfWeek;
import com.thang.chargeops.common.enums.TouRateDayType;
import com.thang.chargeops.common.enums.TouRatePeriodCode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public record StationPricingResponse(
        UUID stationId,

        // 1. Cấu hình cơ bản & thời lượng
        int minBookingDurationMin,       // 30, 60, 90
        int durationStepMinutes,         // 30 (cố định hệ thống)
        int maxDurationMinutes,          // 180 (cố định hệ thống)
        BigDecimal basePriceVnd,         // Giá sạc cơ bản / kWh

        // 2. Giờ hoạt động (7 ngày)
        boolean open24Hours,
        List<OperatingHourResponse> hours,

        // 3. Danh sách biểu giá TOU
        List<TouRuleResponse> touRules,

        // 4. Quy chuẩn khả dụng toàn sàn (System policies)
        AvailabilityPolicyResponse availability,

        // 5. Metadata phiên bản lịch đang áp dụng
        Instant scheduleEffectiveFrom,
        Instant scheduleEffectiveTo,
        String scheduleStatus,
        Long version
) {
    // Giờ hoạt động từng ngày
    public record OperatingHourResponse(
            StationDayOfWeek day,  // MONDAY, TUESDAY... hoặc T2..CN
            LocalTime openTime,    // "06:00" (null nếu đóng cửa cả ngày)
            LocalTime closeTime,   // "23:00" (null nếu đóng cửa cả ngày)
            boolean enabled        // true: mở cửa, false: đóng cửa
    ) {}

    // Khung giá TOU
    public record TouRuleResponse(
            UUID id,
            String name,
            TouRatePeriodCode periodCode,  // NORMAL, PEAK, OFF_PEAK
            TouRateDayType dayType,        // DAILY, WEEKDAY, WEEKEND
            LocalTime startTime,           // "17:00"
            LocalTime endTime,             // "21:00"
            BigDecimal rateVnd             // 4200.00
    ) {}

    // Quy chuẩn SLA vận hành
    public record AvailabilityPolicyResponse(
            boolean autoLock,
            int noShowTimeoutMinutes,      // 15
            int maxAdvanceDays,            // 2
            int bufferMinutes              // 10
    ) {}

}

