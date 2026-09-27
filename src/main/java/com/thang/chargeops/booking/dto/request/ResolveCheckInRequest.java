package com.thang.chargeops.booking.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record ResolveCheckInRequest(
        @NotNull
        UUID bookingId,

        @NotBlank
        @Size(min = 16, max = 512)
        String challengeToken
) {
}
