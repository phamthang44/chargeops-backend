package com.thang.chargeops.refund;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.RefundErrorCode;
import com.thang.chargeops.refund.dto.request.ExecuteRefundRequest;
import com.thang.chargeops.refund.dto.request.RefundExecutionOutcome;
import com.thang.chargeops.refund.executor.ManualRecordRefundExecutor;
import com.thang.chargeops.refund.executor.RefundExecutionCommand;
import com.thang.chargeops.refund.executor.RefundExecutionResult;
import com.thang.chargeops.refund.executor.SimulatorRefundExecutor;
import com.thang.chargeops.refund.model.RefundExecutionMode;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefundExecutorTest {
    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");

    @Test
    void simulatorIsProfileGuardedAndDeterministic() {
        Profile profile = SimulatorRefundExecutor.class.getAnnotation(Profile.class);
        assertThat(profile.value()).containsExactlyInAnyOrder("dev", "demo", "test");

        UUID requestKey = UUID.fromString("11111111-2222-3333-4444-555555555555");
        ExecuteRefundRequest request = new ExecuteRefundRequest(
                0L, RefundExecutionMode.SIMULATOR, RefundExecutionOutcome.SUCCEEDED,
                null, null, "Demo success"
        );
        RefundExecutionCommand command = new RefundExecutionCommand(UUID.randomUUID(), requestKey, request, NOW);

        RefundExecutionResult first = new SimulatorRefundExecutor().execute(command);
        RefundExecutionResult replay = new SimulatorRefundExecutor().execute(command);

        assertThat(first).isEqualTo(replay);
        assertThat(first.transferReference()).isEqualTo("SIM-REF-111111112222");
        assertThat(first.performedAt()).isEqualTo(NOW);
    }

    @Test
    void simulatorFailureIsTerminalWithoutTransferReference() {
        ExecuteRefundRequest request = new ExecuteRefundRequest(
                0L, RefundExecutionMode.SIMULATOR, RefundExecutionOutcome.FAILED,
                null, null, "Gateway timeout demo"
        );
        RefundExecutionResult result = new SimulatorRefundExecutor().execute(
                new RefundExecutionCommand(UUID.randomUUID(), UUID.randomUUID(), request, NOW)
        );

        assertThat(result.outcome()).isEqualTo(RefundExecutionOutcome.FAILED);
        assertThat(result.transferReference()).isNull();
        assertThat(result.failureCode()).isEqualTo("SIMULATED_FAILURE");
    }

    @Test
    void manualRecordRejectsFailedOutcome() {
        ManualRecordRefundExecutor executor = new ManualRecordRefundExecutor();
        ExecuteRefundRequest invalid = new ExecuteRefundRequest(
                0L, RefundExecutionMode.MANUAL_RECORD, RefundExecutionOutcome.FAILED,
                null, null, "No transfer"
        );

        assertThatThrownBy(() -> executor.execute(
                new RefundExecutionCommand(UUID.randomUUID(), UUID.randomUUID(), invalid, NOW)
        )).isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(RefundErrorCode.INVALID_EXECUTION_REQUEST));
    }

    @Test
    void manualRecordRejectsMissingTransferReference() {
        ManualRecordRefundExecutor executor = new ManualRecordRefundExecutor();
        ExecuteRefundRequest invalid = new ExecuteRefundRequest(
                0L, RefundExecutionMode.MANUAL_RECORD, RefundExecutionOutcome.SUCCEEDED,
                null, NOW.minusSeconds(60), "Đã chuyển khoản ngoài hệ thống"
        );

        assertThatThrownBy(() -> executor.execute(
                new RefundExecutionCommand(UUID.randomUUID(), UUID.randomUUID(), invalid, NOW)
        )).isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(RefundErrorCode.INVALID_EXECUTION_REQUEST));
    }

    @Test
    void manualRecordRejectsMissingPerformedAt() {
        ManualRecordRefundExecutor executor = new ManualRecordRefundExecutor();
        ExecuteRefundRequest invalid = new ExecuteRefundRequest(
                0L, RefundExecutionMode.MANUAL_RECORD, RefundExecutionOutcome.SUCCEEDED,
                "FT260925987123", null, "Đã chuyển khoản ngoài hệ thống"
        );

        assertThatThrownBy(() -> executor.execute(
                new RefundExecutionCommand(UUID.randomUUID(), UUID.randomUUID(), invalid, NOW)
        )).isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(RefundErrorCode.INVALID_EXECUTION_REQUEST));
    }

    @Test
    void manualRecordAcceptsSuccessfulPastExternalTransfer() {
        ManualRecordRefundExecutor executor = new ManualRecordRefundExecutor();

        Instant performedAt = NOW.minusSeconds(60);
        ExecuteRefundRequest valid = new ExecuteRefundRequest(
                0L, RefundExecutionMode.MANUAL_RECORD, RefundExecutionOutcome.SUCCEEDED,
                " FT260925987123 ", performedAt, "Đã chuyển khoản ngoài hệ thống"
        );
        RefundExecutionResult result = executor.execute(
                new RefundExecutionCommand(UUID.randomUUID(), UUID.randomUUID(), valid, NOW)
        );
        assertThat(result.transferReference()).isEqualTo("FT260925987123");
        assertThat(result.performedAt()).isEqualTo(performedAt);
    }
}

