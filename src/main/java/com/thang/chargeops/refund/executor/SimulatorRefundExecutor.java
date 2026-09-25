package com.thang.chargeops.refund.executor;

import com.thang.chargeops.refund.dto.request.RefundExecutionOutcome;
import com.thang.chargeops.refund.model.RefundExecutionMode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
@Profile({"dev", "demo", "test"})
public class SimulatorRefundExecutor implements RefundExecutor {

    @Override
    public RefundExecutionMode mode() {
        return RefundExecutionMode.SIMULATOR;
    }

    @Override
    public RefundExecutionResult execute(RefundExecutionCommand command) {
        RefundExecutionOutcome outcome = command.request().outcome();
        if (outcome == RefundExecutionOutcome.SUCCEEDED) {
            String compactKey = command.requestKey().toString().replace("-", "")
                    .substring(0, 12).toUpperCase(Locale.ROOT);
            return new RefundExecutionResult(
                    outcome,
                    "SIMULATOR",
                    "SIM-REF-" + compactKey,
                    null,
                    command.executionAt(),
                    command.request().note().trim()
            );
        }
        return new RefundExecutionResult(
                outcome,
                "SIMULATOR",
                null,
                "SIMULATED_FAILURE",
                command.executionAt(),
                command.request().note().trim()
        );
    }
}

