package com.thang.chargeops.booking.dto.filter;

import com.thang.chargeops.common.enums.BookingStatus;

import java.time.Instant;
import java.util.UUID;

public record OwnerBookingFilter(
        UUID stationId,
        UUID connectorId,
        Instant from,
        Instant to,
        BookingStatus status,
        BookingWindowMode windowMode
) {
    public OwnerBookingFilter(UUID stationId, UUID connectorId, Instant from, Instant to,
                              BookingStatus status) {
        this(stationId, connectorId, from, to, status, BookingWindowMode.OVERLAP);
    }

    public OwnerBookingFilter {
        if (windowMode == null) windowMode = BookingWindowMode.OVERLAP;
    }
}
