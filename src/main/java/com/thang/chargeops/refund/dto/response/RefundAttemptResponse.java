package com.thang.chargeops.refund.dto.response;

import com.thang.chargeops.refund.model.RefundAttemptStatus;
import com.thang.chargeops.refund.model.RefundExecutionMode;

import java.time.Instant;
import java.util.UUID;

public record RefundAttemptResponse(
        UUID attemptId,
        int sequenceNo,
        RefundExecutionMode executionMode,
        RefundAttemptStatus status,
        String transferReference,
        String failureCode,
        String note,
        Instant startedAt,
        Instant performedAt,
        Instant completedAt,
        UUID performedBy
) {
}

