package com.thang.chargeops.booking.checkin;

import java.time.Duration;
import java.util.UUID;

public interface CheckInChallengeService {

    long CHALLENGE_TTL_SECONDS = 60L;
    Duration CHALLENGE_TTL = Duration.ofSeconds(CHALLENGE_TTL_SECONDS);

    String create(UUID connectorId);

    UUID resolve(String token);

    void consume(String token);

    void validateAndConsume(String token, UUID expectedConnectorId);

}
