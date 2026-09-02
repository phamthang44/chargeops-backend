package com.thang.chargeops.booking.policy.model;

import com.thang.chargeops.booking.enums.CancellationRefundTier;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Kết quả tính toán của cancellation policy cho một booking cụ thể.
 */
public record CancellationRefundDecision(
        CancellationRefundTier tier,
        int refundPercent,
        BigDecimal refundAmount,
        BigDecimal cancellationFee,
        Instant graceEndsAt
) {
}
