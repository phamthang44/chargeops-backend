package com.thang.chargeops.booking.dto.request;

import com.thang.chargeops.common.enums.PaymentMethod;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.UUID;

public record CreateBookingRequest(
        @NotNull
        UUID connectorId,

        @NotNull
        Instant startAt,

        @Min(30)
        int durationMin,

        //Consent của người dùng
        @NotNull
        @PositiveOrZero
        Long acceptedTotalAmount,
        @NotBlank
        @Pattern(regexp = "^[0-9a-f]{64}$")
        String acceptedPricingVersion,

        @NotBlank String acceptedPolicyVersion,
        @NotNull PaymentMethod paymentMethod
) {
}
