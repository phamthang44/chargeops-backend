package com.thang.chargeops.booking.checkin;

public record IssuedCheckInChallenge(
        String token,
        long expiresInSeconds
) {
}
