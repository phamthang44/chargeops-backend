package com.thang.chargeops.station.policy;

import com.thang.chargeops.station.entity.Station;

import java.time.Instant;

public interface StationBusinessEligibilityPolicy {
    void requireEligibleForNewBusiness(Station station, Instant at);
    // Station ACTIVE + effective License

    boolean isEligibleForNewBusiness(
            Station station,
            Instant at
    );
}
