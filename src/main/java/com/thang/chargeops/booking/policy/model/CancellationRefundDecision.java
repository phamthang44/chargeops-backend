package com.thang.chargeops.booking.policy.model;

import com.thang.chargeops.booking.enums.CancellationRefundTier;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Kết quả tính toán của cancellation policy cho một booking cụ thể.
 * cancellationFee is the retained package balance, not a platform fee or commission.
 * graceEndsAt is the stored deadline capped at start; null is allowed for unpaid bookings.
 */
public record CancellationRefundDecision(
        CancellationRefundTier tier,
        int refundPercent,
        BigDecimal refundAmount,
        BigDecimal cancellationFee,
        Instant graceEndsAt
) {
}
