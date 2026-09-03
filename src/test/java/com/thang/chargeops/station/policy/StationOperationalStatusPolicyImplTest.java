package com.thang.chargeops.station.policy;

import com.thang.chargeops.common.enums.StationOperationalStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.entity.StationOperatingSchedule;
import com.thang.chargeops.station.policy.impl.StationOperationalStatusPolicyImpl;
import com.thang.chargeops.station.repository.StationOperatingScheduleRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StationOperationalStatusPolicyImplTest {

    private static final Instant NOW = Instant.parse("2026-09-03T03:00:00Z");

    private final StationVisibilityPolicy visibilityPolicy = mock(StationVisibilityPolicy.class);
    private final StationOperatingScheduleRepository scheduleRepository =
            mock(StationOperatingScheduleRepository.class);
    private final StationOperationalStatusPolicy policy =
            new StationOperationalStatusPolicyImpl(visibilityPolicy, scheduleRepository);

    @Test
    void requiresReasonWhenOwnerStopsOperations() {
        Station station = new Station();

        assertThatThrownBy(() -> policy.requireCanChange(
                station,
                StationOperationalStatus.PAUSED,
                "  ",
                NOW
        )).isInstanceOfSatisfying(
                AppException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(StationErrorCode.STATION_OPERATIONAL_STATUS_REASON_REQUIRED)
        );
        verifyNoInteractions(visibilityPolicy, scheduleRepository);
    }

    @Test
    void reopeningRequiresVisibilityAndActiveSchedule() {
        UUID stationId = UUID.randomUUID();
        Station station = new Station();
        station.setId(stationId);
        when(visibilityPolicy.isVisibleToDrivers(station, NOW)).thenReturn(true);
        when(scheduleRepository.findActiveByStationId(stationId, NOW))
                .thenReturn(Optional.of(mock(StationOperatingSchedule.class)));

        policy.requireCanChange(
                station,
                StationOperationalStatus.OPERATING,
                null,
                NOW
        );
    }

    @Test
    void rejectsReopeningWhenStationIsHiddenByPlatformRules() {
        Station station = new Station();
        when(visibilityPolicy.isVisibleToDrivers(station, NOW)).thenReturn(false);

        assertThatThrownBy(() -> policy.requireCanChange(
                station,
                StationOperationalStatus.OPERATING,
                null,
                NOW
        )).isInstanceOfSatisfying(
                AppException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(StationErrorCode.STATION_NOT_ELIGIBLE_FOR_NEW_BUSINESS)
        );
        verifyNoInteractions(scheduleRepository);
    }
}
