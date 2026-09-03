package com.thang.chargeops.station.policy;

import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.policy.impl.StationVisibilityPolicyImpl;
import com.thang.chargeops.station.repository.LicenseRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StationVisibilityPolicyImplTest {

    private static final Instant NOW = Instant.parse("2026-09-03T03:00:00Z");

    private final LicenseRepository licenseRepository = mock(LicenseRepository.class);
    private final StationVisibilityPolicy policy =
            new StationVisibilityPolicyImpl(licenseRepository);

    @Test
    void exposesOnlyActiveStationWithEffectiveLicense() {
        UUID stationId = UUID.randomUUID();
        Station station = Station.builder().status(StationStatus.ACTIVE).build();
        station.setId(stationId);
        when(licenseRepository.existsActiveLicenseForStation(stationId, NOW))
                .thenReturn(true);

        assertThat(policy.isVisibleToDrivers(station, NOW)).isTrue();
    }

    @Test
    void hidesPlatformSuspendedStationBeforeLicenseLookup() {
        Station station = Station.builder().status(StationStatus.SUSPENDED).build();

        assertThat(policy.isVisibleToDrivers(station, NOW)).isFalse();
        verifyNoInteractions(licenseRepository);
    }
}
