package com.thang.chargeops.booking.policy.model;

import java.util.List;

/**
 * Dữ liệu read-only dùng để công bố cancellation policy cho Driver.
 */
public record CancellationPolicySummary(
        int gracePeriodMinutes,
        List<CancellationRefundRule> refundRules
) {
    public CancellationPolicySummary {
        refundRules = List.copyOf(refundRules);
    }
}
