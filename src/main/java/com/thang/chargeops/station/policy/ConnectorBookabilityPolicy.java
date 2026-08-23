package com.thang.chargeops.station.policy;

import com.thang.chargeops.station.entity.Connector;

import java.time.Instant;

public interface ConnectorBookabilityPolicy {
    void requireBookableForNewBooking(Connector connector, Instant at);
    // gọi StationBusinessEligibilityPolicy
    // + CP provisioning ACTIVE
    // + CP operational AVAILABLE
    // + Connector AVAILABLE
}
