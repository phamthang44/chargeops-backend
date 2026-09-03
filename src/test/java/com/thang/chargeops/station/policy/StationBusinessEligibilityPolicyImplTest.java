package com.thang.chargeops.station.policy;

import com.thang.chargeops.common.enums.StationOperationalStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.policy.impl.StationBusinessEligibilityPolicyImpl;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StationBusinessEligibilityPolicyImplTest {

    private static final Instant NOW = Instant.parse("2026-09-03T03:00:00Z");

    private final StationVisibilityPolicy visibilityPolicy = mock(StationVisibilityPolicy.class);
    private final StationBusinessEligibilityPolicy policy =
            new StationBusinessEligibilityPolicyImpl(visibilityPolicy);

    @Test
    void acceptsNewBusinessOnlyWhenVisibleAndOwnerIsOperating() {
        Station station = Station.builder()
                .operationalStatus(StationOperationalStatus.OPERATING)
                .build();
        when(visibilityPolicy.isVisibleToDrivers(station, NOW)).thenReturn(true);

        assertThat(policy.isEligibleForNewBusiness(station, NOW)).isTrue();
    }

    @Test
    void keepsPausedStationVisibleButRejectsNewBusiness() {
        Station station = Station.builder()
                .operationalStatus(StationOperationalStatus.PAUSED)
                .build();
        when(visibilityPolicy.isVisibleToDrivers(station, NOW)).thenReturn(true);

        assertThat(policy.isEligibleForNewBusiness(station, NOW)).isFalse();
        assertThatThrownBy(() -> policy.requireEligibleForNewBusiness(station, NOW))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(StationErrorCode.STATION_NOT_ELIGIBLE_FOR_NEW_BUSINESS)
                );
    }
}
