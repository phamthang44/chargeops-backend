package com.thang.chargeops.booking.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

public record PricePreviewRequest(
        @NotNull
        UUID connectorId,

        @NotNull
        Instant startAt,

        @Min(30)
        int durationMin
) {
}
