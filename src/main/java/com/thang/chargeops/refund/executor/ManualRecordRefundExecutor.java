package com.thang.chargeops.refund.executor;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.RefundErrorCode;
import com.thang.chargeops.refund.dto.request.RefundExecutionOutcome;
import com.thang.chargeops.refund.model.RefundExecutionMode;
import org.springframework.stereotype.Component;

@Component
public class ManualRecordRefundExecutor implements RefundExecutor {

    @Override
    public RefundExecutionMode mode() {
        return RefundExecutionMode.MANUAL_RECORD;
    }

    @Override
    public RefundExecutionResult execute(RefundExecutionCommand command) {
        String reference = normalize(command.request().transferReference());
        if (command.request().outcome() != RefundExecutionOutcome.SUCCEEDED
                || reference == null
                || command.request().performedAt() == null
                || command.request().performedAt().isAfter(command.executionAt())) {
            throw new AppException(
                    RefundErrorCode.INVALID_EXECUTION_REQUEST,
                    "MANUAL_RECORD requires a completed external transfer reference and performedAt not in the future"
            );
        }
        return new RefundExecutionResult(
                RefundExecutionOutcome.SUCCEEDED,
                null,
                reference,
                null,
                command.request().performedAt(),
                command.request().note().trim()
        );
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}

