package com.thang.chargeops.booking.checkin;

public record CheckInChallengeResponse(
        String challengeToken,
        long expiresInSeconds
) {
}
