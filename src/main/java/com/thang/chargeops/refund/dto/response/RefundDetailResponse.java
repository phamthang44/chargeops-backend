package com.thang.chargeops.refund.dto.response;

import com.thang.chargeops.refund.model.RefundBasisType;
import com.thang.chargeops.refund.model.RefundReason;
import com.thang.chargeops.refund.model.RefundStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RefundDetailResponse(
        UUID refundId,
        UUID bookingId,
        String bookingCode,
        UUID driverId,
        String driverName,
        String stationName,
        long amount,
        String currency,
        RefundReason reason,
        RefundBasisType basisType,
        UUID basisId,
        RefundStatus status,
        long version,
        Instant decisionAt,
        UUID decidedBy,
        UUID successfulAttemptId,
        String transferReference,
        Instant completedAt,
        List<RefundAttemptResponse> attempts
) {
}

