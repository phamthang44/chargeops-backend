package com.thang.chargeops.booking.command;

import lombok.Builder;

import java.util.UUID;

/**
 * Canonical representation of a Driver cancel booking command payload for idempotent hashing.
 * Path parameter bookingId is strictly included as part of the command identity.
 */
@Builder
public record CancelBookingCanonicalPayload(
        UUID bookingId,
        Long expectedVersion,
        Long expectedRefundAmount,
        String acceptedPolicyVersion
) {
}
