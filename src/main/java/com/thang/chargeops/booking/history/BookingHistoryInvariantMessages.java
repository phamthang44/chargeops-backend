package com.thang.chargeops.booking.history;

import com.thang.chargeops.common.enums.BookingStatus;

/** Internal developer-facing messages for Booking history invariants. */
final class BookingHistoryInvariantMessages {

    static final String USER_TRANSITION_REQUIRES_HUMAN_ACTOR =
            "A user transition requires a non-SYSTEM actor type";
    static final String COMMAND_REQUIRED =
            "command must not be null for a user transition";
    static final String BOOKING_REQUIRED = "booking must not be null";
    static final String TO_STATUS_REQUIRED = "toStatus must not be null";
    static final String OCCURRED_AT_REQUIRED = "occurredAt must not be null";
    static final String ONLY_PENDING_CREATION =
            "Only a PENDING Booking can record its creation transition";
    static final String TRANSITION_REQUIRED = "transition must not be null";
    static final String BOOKING_STATUS_REQUIRED = "booking status must not be null";

    private BookingHistoryInvariantMessages() {
    }

    static String invalidTransition(BookingStatus fromStatus, BookingStatus toStatus) {
        return "Invalid Booking status transition: " + fromStatus + " -> " + toStatus;
    }
}
