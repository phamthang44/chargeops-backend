package com.thang.chargeops.refund.executor;

import com.thang.chargeops.refund.dto.request.RefundExecutionOutcome;

import java.time.Instant;

public record RefundExecutionResult(
        RefundExecutionOutcome outcome,
        String providerRefundId,
        String transferReference,
        String failureCode,
        Instant performedAt,
        String note
) {
}

