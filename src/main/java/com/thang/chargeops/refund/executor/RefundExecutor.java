package com.thang.chargeops.refund.executor;

import com.thang.chargeops.refund.model.RefundExecutionMode;

public interface RefundExecutor {
    RefundExecutionMode mode();
    RefundExecutionResult execute(RefundExecutionCommand command);
}

