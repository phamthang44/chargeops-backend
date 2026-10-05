package com.thang.chargeops.booking.dto.filter;

import java.time.Instant;
import java.util.UUID;

public record StationOperationalBookingFilter(
        UUID connectorId,
        Instant from,
        Instant to
) {}
