package com.thang.chargeops.support.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.thang.chargeops.booking.dto.request.OwnerCancelBookingRequest;
import com.thang.chargeops.support.model.RefundPolicyOutcome;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record ReviewRefundPolicyRequest(
        @NotNull @Min(0) Long expectedVersion,
        @NotNull @Min(0) Long expectedBookingVersion,
        @NotNull @Min(0) Long expectedDecisionVersion,
        @NotNull RefundPolicyOutcome outcome,
        Instant affectedAt,
        @NotNull @Size(min = 5, max = 2000) String reason) {
    public ReviewRefundPolicyRequest { reason = OwnerCancelBookingRequest.normalizeReason(reason); }
    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("Unsupported policy field: " + field);
    }
}
