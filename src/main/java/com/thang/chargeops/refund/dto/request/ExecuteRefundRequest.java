package com.thang.chargeops.refund.dto.request;

import com.thang.chargeops.refund.model.RefundExecutionMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record ExecuteRefundRequest(
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotNull RefundExecutionMode executionMode,
        @NotNull RefundExecutionOutcome outcome,
        @Size(max = 128) String transferReference,
        Instant performedAt,
        @NotBlank @Size(max = 2000) String note
) {
}

