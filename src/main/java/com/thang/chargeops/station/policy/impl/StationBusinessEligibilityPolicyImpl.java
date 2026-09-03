package com.thang.chargeops.station.policy.impl;

import com.thang.chargeops.common.enums.StationOperationalStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.policy.StationBusinessEligibilityPolicy;
import com.thang.chargeops.station.policy.StationVisibilityPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
public class StationBusinessEligibilityPolicyImpl implements StationBusinessEligibilityPolicy {

    private final StationVisibilityPolicy visibilityPolicy;

    @Override
    public void requireEligibleForNewBusiness(Station station, Instant at) {
        boolean eligible = isEligibleForNewBusiness(station, at);

        if (!eligible) {
            throw new AppException(
                    StationErrorCode.STATION_NOT_ELIGIBLE_FOR_NEW_BUSINESS
            );
        }
    }

    @Override
    public boolean isEligibleForNewBusiness(
            Station station,
            Instant at
    ) {
        return visibilityPolicy.isVisibleToDrivers(station, at)
                && station.getOperationalStatus() == StationOperationalStatus.OPERATING;
    }

}
