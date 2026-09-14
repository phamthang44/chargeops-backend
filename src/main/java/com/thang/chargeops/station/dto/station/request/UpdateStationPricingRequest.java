package com.thang.chargeops.station.dto.station.request;

import com.thang.chargeops.common.enums.StationDayOfWeek;
import com.thang.chargeops.common.enums.TouRateDayType;
import com.thang.chargeops.common.enums.TouRatePeriodCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public record UpdateStationPricingRequest(
        @NotNull
        @Min(30)
        @Max(90)
        Integer minBookingDurationMin, // Phải là 30, 60, hoặc 90
        @NotNull
        @DecimalMin(value = "0.0", inclusive = false)
        @Digits(integer = 13, fraction = 2)
        BigDecimal basePriceVnd,       // Giá cơ bản > 0
        boolean open24Hours,
        @NotNull
        @Size(min = 7, max = 7)
        @Valid
        List<OperatingHourRequest> hours, // Đủ 7 ngày trong tuần
        @NotNull
        @Valid
        List<TouRuleRequest> touRules,     // Danh sách khung giá TOU (có thể rỗng nếu chỉ dùng base price)
        @NotNull
        Long version
) {
    public record OperatingHourRequest(
            @NotNull
            StationDayOfWeek day,

            LocalTime openTime,

            LocalTime closeTime,

            boolean enabled
    ) {}

    public record TouRuleRequest(
            UUID id,                       // null nếu tạo mới, có UUID nếu sửa rule cũ

            @NotBlank
            @Size(max = 100)
            String name,

            @NotNull
            TouRatePeriodCode periodCode,  // PEAK, OFF_PEAK, NORMAL

            @NotNull
            TouRateDayType dayType,        // DAILY, WEEKDAY, WEEKEND

            @NotNull
            LocalTime startTime,

            @NotNull
            LocalTime endTime,

            @NotNull
            @DecimalMin(value = "0.0", inclusive = false)
            @Digits(integer = 13, fraction = 2)
            BigDecimal rateVnd
    ) {}

}
