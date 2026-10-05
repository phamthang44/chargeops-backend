package com.thang.chargeops.booking.dto.filter;

import com.thang.chargeops.common.enums.BookingStatus;

import java.time.Instant;
import java.util.UUID;

public record OwnerBookingFilter(
        UUID stationId,
        UUID connectorId,
        Instant from,
        Instant to,
        BookingStatus status
) {}
