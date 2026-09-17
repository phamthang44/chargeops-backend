package com.thang.chargeops.booking.dto.response;

import java.math.BigDecimal;

public record BookingStatsResponse(
        BigDecimal totalSpending,
        Long totalChargingSessions,
        Long totalBookings,
        Long totalCompletedBookings,
        Long totalCancelledBookings,
        Double totalHours,
        BigDecimal spent,
        Long sessions,
        Double hours
) {

    public BookingStatsResponse(
            BigDecimal totalSpending,
            Long totalChargingSessions,
            Long totalBookings,
            Long totalCompletedBookings,
            Long totalCancelledBookings
    ) {
        this(
                totalSpending,
                totalChargingSessions,
                totalBookings,
                totalCompletedBookings,
                totalCancelledBookings,
                0.0,
                totalSpending,
                totalChargingSessions,
                0.0
        );
    }

    public static BookingStatsResponse of(
            BigDecimal totalSpending,
            Long totalChargingSessions,
            Long totalBookings,
            Long totalCompletedBookings,
            Long totalCancelledBookings,
            Double totalHours
    ) {
        return new BookingStatsResponse(
                totalSpending,
                totalChargingSessions,
                totalBookings,
                totalCompletedBookings,
                totalCancelledBookings,
                totalHours,
                totalSpending,
                totalChargingSessions,
                totalHours
        );
    }
}
