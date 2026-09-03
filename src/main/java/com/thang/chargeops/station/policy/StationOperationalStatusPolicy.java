package com.thang.chargeops.station.policy;

import com.thang.chargeops.common.enums.StationOperationalStatus;
import com.thang.chargeops.station.entity.Station;

import java.time.Instant;

public interface StationOperationalStatusPolicy {

    void requireCanChange(
            Station station,
            StationOperationalStatus targetStatus,
            String reason,
            Instant at
    );
}
