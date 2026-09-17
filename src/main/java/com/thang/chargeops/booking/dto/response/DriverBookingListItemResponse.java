package com.thang.chargeops.booking.dto.response;

import com.thang.chargeops.common.enums.BookingStatus;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/**
 * Lightweight Driver-facing projection for the active and history lists.
 *
 * <p>The status is the effective status at response time. Audit-only data from
 * booking_commands and booking_status_history is deliberately not exposed.</p>
 */
@Builder
public record DriverBookingListItemResponse(
        UUID bookingId,
        String bookingCode,

        BookingStatus status,
        BookingStatus persistedStatus,
        boolean stateReconciliationPending,
        BookingCancellationReason cancellationReason,

        long version,

        StationSnapshotResponse station,
        String timezone,

        Instant startAt,
        Instant endAt,
        int durationMin,

        long totalAmount,
        String currency,

        Instant paymentHoldExpiresAt,
        Instant freeCancellationDeadline,
        Instant checkInOpensAt,
        Instant checkInDeadline,
        Instant checkedInAt,
        Instant chargingStartedAt,

        BookingActionsResponse actions
) {
}
