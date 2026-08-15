package com.thang.chargeops.station.policy;

import com.thang.chargeops.station.entity.Station;

public interface StationApprovalPolicy {

    void requireCanBeApproved(Station station);
    void requireCanBeRejected(Station station, String reason);

}
