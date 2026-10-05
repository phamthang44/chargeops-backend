package com.thang.chargeops.booking.command;

/** Stable namespaces for retryable commands that act on a Booking. */
public enum BookingCommandOperation {
    CREATE_BOOKING,
    CREATE_CHECKOUT,
    CANCEL_BOOKING,
    OWNER_CANCEL_BOOKING,
    OWNER_ACCEPT_STATION_FAILURE,
    ADMIN_REVIEW_STATION_FAILURE,
    CONFIRM_CHECK_IN,
    START_CHARGING,
    COMPLETE_BOOKING,
    RESOLVE_INCIDENT_SESSION,
    SIMULATE_PAYMENT
}
