package com.thang.chargeops.booking.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record OwnerAcceptStationFailureRequest(
        @NotNull @Min(0) Long expectedVersion,
        @NotNull @Min(0) Long expectedDecisionVersion,
        @NotNull Instant affectedAt,
        @NotNull @Size(min = 5, max = 2000) String reason) {

    public OwnerAcceptStationFailureRequest {
        reason = OwnerCancelBookingRequest.normalizeReason(reason);
    }

    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("Unsupported command field: " + field);
    }
}
