package com.thang.chargeops.booking.dto.response;

import lombok.Builder;

/** Server-evaluated capabilities; clients must not recalculate these rules. */
@Builder
public record BookingActionsResponse(
        boolean canCancel,
        long refundableAmount,
        CancellationCapabilityReason cancellationReason,
        boolean canCheckIn,
        CheckInCapabilityReason checkInReason,
        boolean canStartCharging,
        boolean canComplete,
        boolean canReportIssue
) {
    public enum CancellationCapabilityReason {
        UNPAID,
        WITHIN_GRACE,
        GRACE_ENDED,
        NOT_CANCELLABLE
    }

    public enum CheckInCapabilityReason {
        AVAILABLE,
        TOO_EARLY,
        WINDOW_CLOSED,
        WRONG_STATE,
        STATION_UNAVAILABLE
    }
}
