package com.thang.chargeops.booking.history;

/** Stable reason codes persisted in booking_status_history.reason. */
public enum BookingStatusReason {
    HOLD_EXPIRED,
    DRIVER_CANCELLED,
    OWNER_CANCELLED,
    PAYMENT_CONFIRMED,
    CHECK_IN_CONFIRMED,
    CHARGING_STARTED,
    SESSION_COMPLETED,
    NO_SHOW
}
