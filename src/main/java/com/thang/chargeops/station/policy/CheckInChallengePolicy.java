package com.thang.chargeops.station.policy;

import com.thang.chargeops.station.entity.Connector;

import java.time.Instant;

/**
 * Eligibility for issuing a short-lived QR challenge to the trusted simulator.
 * It deliberately does not re-check license entitlement after booking.
 */
public interface CheckInChallengePolicy {

    void requireCanIssue(Connector connector, Instant at);
}
