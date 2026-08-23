package com.thang.chargeops.station.policy.impl;

import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.policy.StationBusinessEligibilityPolicy;
import com.thang.chargeops.station.repository.LicenseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
public class StationBusinessEligibilityPolicyImpl implements StationBusinessEligibilityPolicy {

    private final LicenseRepository licenseRepository;

    @Override
    public void requireEligibleForNewBusiness(Station station, Instant at) {
        boolean eligible = station.getStatus() == StationStatus.ACTIVE
                && licenseRepository.existsActiveLicenseForStation(station.getId(), at);

        if (!eligible) {
            throw new AppException(
                    StationErrorCode.STATION_NOT_ELIGIBLE_FOR_NEW_BUSINESS
            );
        }
    }
}
