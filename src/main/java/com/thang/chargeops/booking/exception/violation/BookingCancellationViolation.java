package com.thang.chargeops.booking.exception.violation;

/**
 * Các invariant mà cancellation policy cần để tính refund an toàn.
 */
public enum BookingCancellationViolation {
    BOOKING_REQUIRED,
    BOOKING_CREATED_AT_REQUIRED,
    BOOKING_START_AT_REQUIRED,
    BOOKING_AMOUNT_REQUIRED,
    CANCELLATION_TIME_REQUIRED,
    BOOKING_AMOUNT_NEGATIVE
}
