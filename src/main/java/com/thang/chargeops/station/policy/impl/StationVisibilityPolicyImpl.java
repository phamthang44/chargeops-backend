package com.thang.chargeops.station.policy.impl;

import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.policy.StationVisibilityPolicy;
import com.thang.chargeops.station.repository.LicenseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
public class StationVisibilityPolicyImpl implements StationVisibilityPolicy {

    private final LicenseRepository licenseRepository;

    @Override
    public boolean isVisibleToDrivers(Station station, Instant at) {
        return station != null
                && at != null
                && station.getStatus() == StationStatus.ACTIVE
                && licenseRepository.existsActiveLicenseForStation(
                station.getId(),
                at
        );
    }
}
