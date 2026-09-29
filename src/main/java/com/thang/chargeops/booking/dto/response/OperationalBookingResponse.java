package com.thang.chargeops.booking.dto.response;

import com.thang.chargeops.common.enums.BookingStatus;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record OperationalBookingResponse(
        UUID bookingId, String bookingCode, BookingStatus status,
        BookingCancellationReason cancellationReason, UUID stationId, UUID connectorId,
        String connectorCode, String driverDisplayName, Instant startAt, Instant endAt,
        Instant checkInDeadline, Instant checkedInAt
) {}
