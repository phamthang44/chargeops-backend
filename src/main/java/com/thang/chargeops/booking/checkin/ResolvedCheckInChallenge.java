package com.thang.chargeops.booking.checkin;

import java.time.Instant;
import java.util.UUID;

/**
 * Resolved details of a valid check-in challenge token.
 * Contains the associated connector ID and the exact instant when this challenge expires.
 */
public record ResolvedCheckInChallenge(
        UUID connectorId,
        Instant expiresAt
) {
}
