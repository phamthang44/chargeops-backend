package com.thang.chargeops.refund.dto.response;

import com.thang.chargeops.refund.model.RefundAttemptStatus;
import com.thang.chargeops.refund.model.RefundExecutionMode;
import com.thang.chargeops.refund.model.RefundExecutionTrigger;

import java.time.Instant;
import java.util.UUID;

public record RefundAttemptResponse(
        UUID attemptId,
        int sequenceNo,
        RefundExecutionMode executionMode,
        RefundExecutionTrigger executionTrigger,
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

