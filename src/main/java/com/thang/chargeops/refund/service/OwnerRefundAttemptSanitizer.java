package com.thang.chargeops.refund.service;

import com.thang.chargeops.refund.dto.response.RefundAttemptResponse;
import com.thang.chargeops.refund.model.RefundAttemptStatus;

/** Sanitizes the Owner read model without rewriting historical execution evidence. */
public final class OwnerRefundAttemptSanitizer {
    private OwnerRefundAttemptSanitizer() { }

    public static RefundAttemptResponse sanitize(RefundAttemptResponse attempt) {
        if (attempt.status() != RefundAttemptStatus.FAILED) return attempt;
        String safeCode = "SIMULATED_FAILURE".equals(attempt.failureCode())
                ? "SIMULATED_FAILURE" : "REFUND_EXECUTION_FAILED";
        return new RefundAttemptResponse(attempt.attemptId(), attempt.sequenceNo(), attempt.executionMode(),
                attempt.executionTrigger(), attempt.status(), attempt.transferReference(), safeCode,
                "Refund execution failed. You can request a retry.", attempt.startedAt(), attempt.performedAt(),
                attempt.completedAt(), attempt.performedBy());
    }
}
