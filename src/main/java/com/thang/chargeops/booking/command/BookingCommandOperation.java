package com.thang.chargeops.booking.command;

/** Stable namespaces for retryable commands that act on a Booking. */
public enum BookingCommandOperation {
    CREATE_BOOKING,
    CREATE_CHECKOUT,
    CANCEL_BOOKING,
    OWNER_CANCEL_BOOKING,
    CONFIRM_CHECK_IN,
    START_CHARGING,
    COMPLETE_BOOKING,
    SIMULATE_PAYMENT
}
