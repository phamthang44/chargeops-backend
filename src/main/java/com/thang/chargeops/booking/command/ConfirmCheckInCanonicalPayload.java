package com.thang.chargeops.booking.command;

import lombok.Builder;

import java.util.UUID;

/** Canonical identity of an idempotent Driver check-in confirmation. */
@Builder
public record ConfirmCheckInCanonicalPayload(
        UUID bookingId,
        Long expectedVersion,
        String challengeToken
) {
}
