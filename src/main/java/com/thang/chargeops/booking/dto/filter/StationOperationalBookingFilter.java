package com.thang.chargeops.booking.dto.filter;

import java.time.Instant;
import java.util.UUID;

public record StationOperationalBookingFilter(
        UUID connectorId,
        Instant from,
        Instant to,
        BookingWindowMode windowMode
) {
    public StationOperationalBookingFilter(UUID connectorId, Instant from, Instant to) {
        this(connectorId, from, to, BookingWindowMode.OVERLAP);
    }

    public StationOperationalBookingFilter {
        if (windowMode == null) windowMode = BookingWindowMode.OVERLAP;
    }
}
