package com.thang.chargeops.refund.model;

import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.refund.entity.Refund;

import java.time.Instant;
import java.util.UUID;

public record PendingRefundAttemptSpec(
        Refund refund,
        int sequenceNo,
        RefundExecutionMode executionMode,
        UUID requestKey,
        String payloadHash,
        String idempotencyKey,
        UserProfile performedBy,
        Instant startedAt
) {
}
