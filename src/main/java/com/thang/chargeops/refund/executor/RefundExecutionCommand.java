package com.thang.chargeops.refund.executor;

import com.thang.chargeops.refund.dto.request.ExecuteRefundRequest;

import java.time.Instant;
import java.util.UUID;

public record RefundExecutionCommand(
        UUID refundId,
        UUID requestKey,
        ExecuteRefundRequest request,
        Instant executionAt
) {
}

