package com.thang.chargeops.booking.policy.model;

import com.thang.chargeops.booking.enums.CancellationRefundTier;

/**
 * Một rule hiển thị trong bản tóm tắt cancellation policy.
 */
public record CancellationRefundRule(
        CancellationRefundTier tier,
        int refundPercent,
        Integer minMinutesBeforeStartInclusive,
        Integer maxMinutesBeforeStartExclusive,
        boolean appliesToNoShow
) {
}
