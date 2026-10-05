package com.thang.chargeops.booking.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Optimistic-version guard shared by Booking lifecycle commands. */
public record VersionRequest(
        @NotNull
        @Min(0)
        Long expectedVersion
) {
}
