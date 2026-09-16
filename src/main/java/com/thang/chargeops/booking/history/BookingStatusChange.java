package com.thang.chargeops.booking.history;

import com.thang.chargeops.common.enums.BookingStatus;

import java.time.Instant;
import java.util.Objects;

/** Immutable data describing one Booking lifecycle change. */
public record BookingStatusChange(
        BookingStatus fromStatus,
        BookingStatus toStatus,
        BookingStatusReason reason,
        Instant occurredAt
) {

    public BookingStatusChange {
        Objects.requireNonNull(
                toStatus,
                BookingHistoryInvariantMessages.TO_STATUS_REQUIRED
        );
        Objects.requireNonNull(
                occurredAt,
                BookingHistoryInvariantMessages.OCCURRED_AT_REQUIRED
        );
    }
}
