package com.thang.chargeops.booking.history;

/** Stable reason codes persisted in booking_status_history.reason. */
public enum BookingStatusReason {
    HOLD_EXPIRED,
    DRIVER_CANCELLED,
    OWNER_CANCELLED,
    PAYMENT_CONFIRMED,
    NO_SHOW
}
