package com.thang.chargeops.booking.policy.model;

/**
 * Dữ liệu read-only dùng để công bố cancellation policy cho Driver.
 */
public record CancellationPolicySummary(
        String policyVersion,
        int gracePeriodMinutes,
        String graceStartsAt,
        boolean requiresBeforeBookingStart,
        boolean requiresNotCheckedIn,
        int withinGraceRefundPercent,
        int afterGraceRefundPercent,
        int noShowRefundPercent,
        int verifiedStationFailureRefundPercent,
        boolean stationFailureRequiresVerification
) {
}
