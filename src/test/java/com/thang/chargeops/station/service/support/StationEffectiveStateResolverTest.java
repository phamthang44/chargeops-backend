package com.thang.chargeops.station.service.support;

import com.thang.chargeops.common.enums.StationOperatingState;
import com.thang.chargeops.common.enums.StationOperationalStatus;
import com.thang.chargeops.station.entity.StationOperatingSchedule;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StationEffectiveStateResolverTest {

    private static final Instant NOW = Instant.parse("2026-09-03T03:00:00Z");

    private final StationOperatingHoursResolver hoursResolver =
            mock(StationOperatingHoursResolver.class);
    private final StationEffectiveStateResolver resolver =
            new StationEffectiveStateResolver(hoursResolver);

    @Test
    void platformUnavailabilityHasHighestPrecedence() {
        StationOperatingSchedule schedule = activeSchedule();

        StationOperatingStatus status = resolver.resolve(
                false,
                StationOperationalStatus.OPERATING,
                schedule,
                NOW
        );

        assertThat(status.state()).isEqualTo(StationOperatingState.UNAVAILABLE_BY_PLATFORM);
        assertThat(status.openNow()).isFalse();
        assertThat(status.scheduleConfigured()).isTrue();
        verifyNoInteractions(hoursResolver);
    }

    @Test
    void ownerPauseOverridesAnOpenSchedule() {
        StationOperatingSchedule schedule = activeSchedule();

        StationOperatingStatus status = resolver.resolve(
                true,
                StationOperationalStatus.PAUSED,
                schedule,
                NOW
        );

        assertThat(status.state()).isEqualTo(StationOperatingState.PAUSED_BY_OWNER);
        assertThat(status.openNow()).isFalse();
        verifyNoInteractions(hoursResolver);
    }

    @Test
    void delegatesOnlyOperatingStationToHoursResolver() {
        StationOperatingSchedule schedule = activeSchedule();
        when(hoursResolver.resolveStatus(schedule, NOW))
                .thenReturn(StationOperatingStatus.open());

        StationOperatingStatus status = resolver.resolve(
                true,
                StationOperationalStatus.OPERATING,
                schedule,
                NOW
        );

        assertThat(status.state()).isEqualTo(StationOperatingState.OPEN);
    }

    private StationOperatingSchedule activeSchedule() {
        return StationOperatingSchedule.builder()
                .effectiveFrom(NOW.minusSeconds(60))
                .open24Hours(true)
                .build();
    }
}
