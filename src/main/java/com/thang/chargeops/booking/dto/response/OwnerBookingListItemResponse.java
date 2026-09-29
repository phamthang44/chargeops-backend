package com.thang.chargeops.booking.dto.response;

import com.thang.chargeops.common.enums.BookingStatus;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record OwnerBookingListItemResponse(
        UUID bookingId, String bookingCode, BookingStatus status, BookingStatus persistedStatus,
        boolean stateReconciliationPending, BookingCancellationReason cancellationReason,
        UUID stationId, String stationName, UUID connectorId, String connectorCode,
        String driverDisplayName, Instant startAt, Instant endAt, Instant checkInDeadline,
        Instant checkedInAt, long totalAmount, String currency
) {}
