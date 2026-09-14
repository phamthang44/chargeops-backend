package com.thang.chargeops.booking.service.model;

import com.thang.chargeops.booking.pricing.PriceBasis;
import com.thang.chargeops.booking.projection.BookingTimeRangeProjection;
import com.thang.chargeops.station.entity.StationBookingSetting;
import com.thang.chargeops.station.service.model.OperatingWindow;
import com.thang.chargeops.station.service.model.StationPriceRange;
import lombok.Builder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Snapshot dữ liệu mà availability service đã chuẩn bị cho mapper.
 * Builder giúp caller truyền dữ liệu theo tên thay vì một danh sách dài tham số
 * cùng kiểu UUID/Instant/List dễ đặt nhầm vị trí.
 */
@Builder
public record StationAvailabilitySnapshot(
        UUID stationId,
        UUID connectorId,
        LocalDate date,
        Instant generatedAt,
        Instant earliestStartAt,
        Instant dayStart,
        Instant dayEnd,
        Instant coverageStartAt,
        Instant coverageEndAt,
        int minDurationMinutes,
        int durationStepMinutes,
        int maxDurationMinutes,
        StationBookingSetting settings,
        List<OperatingWindow> operatingWindows,
        List<BookingTimeRangeProjection> busyRanges,
        List<StationPriceRange> priceRanges,
        String policyVersion,
        PriceBasis pricingEstimateParameters
) {
    public StationAvailabilitySnapshot {
        operatingWindows = List.copyOf(operatingWindows);
        busyRanges = List.copyOf(busyRanges);
        priceRanges = List.copyOf(priceRanges);
    }
}
