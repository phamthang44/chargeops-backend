package com.thang.chargeops.refund.dto.response;

import com.thang.chargeops.refund.model.RefundReason;
import com.thang.chargeops.refund.model.RefundStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Owner-scoped refund evidence. A completed obligation is not a bank transfer. */
public record OwnerRefundResponse(
        UUID refundId,
        UUID bookingId,
        String bookingCode,
        UUID stationId,
        long amount,
        String currency,
        RefundReason reason,
        RefundStatus status,
        boolean requiresOwnerAction,
        long version,
        Instant decisionAt,
        Instant completedAt,
        List<RefundAttemptResponse> attempts
) {
}
