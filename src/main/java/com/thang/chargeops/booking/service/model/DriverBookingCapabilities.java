package com.thang.chargeops.booking.service.model;

/**
 * Driver capabilities resolved by the booking read policy.
 * This model deliberately has no dependency on the API response DTOs.
 */
public record DriverBookingCapabilities(
        boolean canCancel,
        long refundableAmount,
        CancellationReason cancellationReason,
        boolean canCheckIn,
        CheckInReason checkInReason,
        boolean canStartCharging,
        boolean canComplete,
        boolean canReportIssue
) {
    public enum CancellationReason {
        UNPAID,
        WITHIN_GRACE,
        GRACE_ENDED,
        NOT_CANCELLABLE
    }

    public enum CheckInReason {
        AVAILABLE,
        TOO_EARLY,
        WINDOW_CLOSED,
        WRONG_STATE,
        STATION_UNAVAILABLE
    }
}
