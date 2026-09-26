package com.thang.chargeops.refund.factory;

import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.refund.entity.Refund;
import com.thang.chargeops.refund.entity.RefundAttempt;
import com.thang.chargeops.refund.model.PendingRefundAttemptSpec;
import com.thang.chargeops.refund.model.RefundExecutionMode;
import com.thang.chargeops.refund.model.RefundExecutionTrigger;

import java.time.Instant;
import java.util.UUID;

public final class RefundAttemptFactory {
    private static final String ADMIN_IDEMPOTENCY_PREFIX = "refund-executor:";
    private static final String SYSTEM_IDEMPOTENCY_PREFIX = "refund-auto-first-attempt:";

    private RefundAttemptFactory() {
    }

    public static RefundAttempt adminAttempt(
            Refund refund,
            int sequenceNo,
            RefundExecutionMode executionMode,
            UUID requestKey,
            String payloadHash,
            UserProfile actor,
            Instant startedAt
    ) {
        return start(refund, sequenceNo, executionMode, requestKey, payloadHash,
                ADMIN_IDEMPOTENCY_PREFIX + refund.getId() + ":" + requestKey,
                RefundExecutionTrigger.ADMIN, actor, startedAt);
    }

    public static RefundAttempt automaticFirstAttempt(
            Refund refund,
            UUID requestKey,
            String payloadHash,
            Instant startedAt
    ) {
        return start(refund, 1, RefundExecutionMode.SIMULATOR, requestKey, payloadHash,
                SYSTEM_IDEMPOTENCY_PREFIX + refund.getId(),
                RefundExecutionTrigger.SYSTEM_POLICY, null, startedAt);
    }

    private static RefundAttempt start(
            Refund refund,
            int sequenceNo,
            RefundExecutionMode executionMode,
            UUID requestKey,
            String payloadHash,
            String idempotencyKey,
            RefundExecutionTrigger trigger,
            UserProfile actor,
            Instant startedAt
    ) {
        return RefundAttempt.start(new PendingRefundAttemptSpec(
                refund, sequenceNo, executionMode, requestKey, payloadHash,
                idempotencyKey, trigger, actor, startedAt
        ));
    }
}
