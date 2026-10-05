package com.thang.chargeops.booking.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CancelBookingRequest(
        @NotNull
        @Min(0)
        Long expectedVersion,

        @NotNull
        @Min(0)
        Long expectedRefundAmount,

        @NotBlank
        String acceptedPolicyVersion
) {
}
