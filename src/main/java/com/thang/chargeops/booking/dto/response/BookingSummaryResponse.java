package com.thang.chargeops.booking.dto.response;

public record BookingSummaryResponse(
        long totalBookings, long pending, long confirmed, long inSession,
        long completed, long cancelled, long expired, long noShow
) {
    public BookingSummaryResponse {
        if (totalBookings != pending + confirmed + inSession + completed + cancelled + expired) {
            throw new IllegalArgumentException("Booking summary buckets do not equal totalBookings");
        }
    }
}
