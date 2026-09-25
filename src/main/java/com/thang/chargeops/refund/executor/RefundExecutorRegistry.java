package com.thang.chargeops.refund.executor;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.RefundErrorCode;
import com.thang.chargeops.refund.model.RefundExecutionMode;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class RefundExecutorRegistry {
    private final Map<RefundExecutionMode, RefundExecutor> executors;

    public RefundExecutorRegistry(List<RefundExecutor> executors) {
        EnumMap<RefundExecutionMode, RefundExecutor> byMode = new EnumMap<>(RefundExecutionMode.class);
        for (RefundExecutor executor : executors) {
            RefundExecutor previous = byMode.putIfAbsent(executor.mode(), executor);
            if (previous != null) {
                throw new IllegalStateException("Duplicate refund executor for mode " + executor.mode());
            }
        }
        this.executors = Map.copyOf(byMode);
    }

    public RefundExecutor require(RefundExecutionMode mode) {
        RefundExecutor executor = executors.get(mode);
        if (executor == null) {
            throw new AppException(RefundErrorCode.MODE_UNAVAILABLE);
        }
        return executor;
    }
}

