package com.thang.chargeops.booking.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ConfirmCheckInRequest(
        @NotNull
        @Min(0)
        Long expectedVersion,

        @NotBlank
        @Size(min = 16, max = 512)
        String challengeToken
) {
}
