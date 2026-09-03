package com.thang.chargeops.station.service.support;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.enums.StationDayOfWeek;
import com.thang.chargeops.common.enums.StationOperatingState;
import com.thang.chargeops.station.entity.StationOperatingSchedule;
import com.thang.chargeops.station.repository.StationOperatingScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class StationOperatingHoursResolverTest {

    private static final ZoneId SYSTEM_ZONE_ID =
            ZoneId.of(SystemConstant.SYSTEM_REGION_TIMEZONE);

    @Mock
    private StationOperatingScheduleRepository scheduleRepository;

    private StationOperatingHoursResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new StationOperatingHoursResolver(scheduleRepository);
    }

    @Test
    void returnsFalseWhenScheduleIsMissingOrExpired() {
        Instant at = localInstant(2026, 9, 1, 10, 0);
        StationOperatingSchedule expiredSchedule = StationOperatingSchedule.builder()
                .open24Hours(true)
                .effectiveFrom(at.minusSeconds(7200))
                .effectiveTo(at)
                .build();

        assertThat(resolver.isOpenAt(null, at)).isFalse();
        assertThat(resolver.isOpenAt(expiredSchedule, at)).isFalse();
        assertThat(resolver.resolveStatus(null, at)).satisfies(status -> {
            assertThat(status.openNow()).isFalse();
            assertThat(status.state())
                    .isEqualTo(StationOperatingState.SCHEDULE_NOT_CONFIGURED);
            assertThat(status.scheduleConfigured()).isFalse();
        });
        assertThat(resolver.resolveStatus(expiredSchedule, at).state())
                .isEqualTo(StationOperatingState.SCHEDULE_NOT_CONFIGURED);
    }

    @Test
    void returnsTrueForActiveOpen24HoursSchedule() {
        Instant at = localInstant(2026, 9, 1, 10, 0);
        StationOperatingSchedule schedule = activeSchedule(at, true);

        assertThat(resolver.isOpenAt(schedule, at)).isTrue();
        assertThat(resolver.resolveStatus(schedule, at)).satisfies(status -> {
            assertThat(status.openNow()).isTrue();
            assertThat(status.state()).isEqualTo(StationOperatingState.OPEN);
            assertThat(status.scheduleConfigured()).isTrue();
        });
    }

    @Test
    void usesInclusiveOpeningAndExclusiveClosingBoundary() {
        Instant opening = localInstant(2026, 9, 1, 8, 0);
        Instant closing = localInstant(2026, 9, 1, 22, 0);
        StationOperatingSchedule schedule = activeSchedule(opening, false);
        schedule.addPeriod(
                StationDayOfWeek.TUESDAY,
                LocalTime.of(8, 0),
                LocalTime.of(22, 0),
                true
        );

        assertThat(resolver.isOpenAt(schedule, opening)).isTrue();
        assertThat(resolver.isOpenAt(schedule, closing)).isFalse();
        assertThat(resolver.resolveStatus(schedule, closing).state())
                .isEqualTo(StationOperatingState.CLOSED_BY_SCHEDULE);
    }

    @Test
    void ignoresDisabledOperatingPeriod() {
        Instant at = localInstant(2026, 9, 1, 10, 0);
        StationOperatingSchedule schedule = activeSchedule(at, false);
        schedule.addPeriod(StationDayOfWeek.TUESDAY, null, null, false);

        assertThat(resolver.isOpenAt(schedule, at)).isFalse();
    }

    @Test
    void resolvesBothSidesOfAnOvernightPeriod() {
        Instant sundayAt23 = localInstant(2026, 9, 6, 23, 0);
        Instant mondayAt01 = localInstant(2026, 9, 7, 1, 0);
        Instant mondayAt02 = localInstant(2026, 9, 7, 2, 0);
        StationOperatingSchedule schedule = activeSchedule(sundayAt23, false);
        schedule.addPeriod(
                StationDayOfWeek.SUNDAY,
                LocalTime.of(22, 0),
                LocalTime.of(2, 0),
                true
        );

        assertThat(resolver.isOpenAt(schedule, sundayAt23)).isTrue();
        assertThat(resolver.isOpenAt(schedule, mondayAt01)).isTrue();
        assertThat(resolver.isOpenAt(schedule, mondayAt02)).isFalse();
    }

    @Test
    void returnsTheWholeLocalDayForOpen24HoursAvailability() {
        LocalDate date = LocalDate.of(2026, 9, 7);
        StationOperatingSchedule schedule = activeSchedule(
                localInstant(2026, 9, 7, 10, 0),
                true
        );

        assertThat(resolver.resolveOperatingWindows(schedule, date))
                .singleElement()
                .satisfies(window -> {
                    assertThat(window.startAt())
                            .isEqualTo(localInstant(2026, 9, 7, 0, 0));
                    assertThat(window.endAt())
                            .isEqualTo(localInstant(2026, 9, 8, 0, 0));
                });
    }

    @Test
    void splitsOvernightAvailabilityAtTheRequestedDayBoundary() {
        LocalDate monday = LocalDate.of(2026, 9, 7);
        StationOperatingSchedule schedule = activeSchedule(
                localInstant(2026, 9, 7, 10, 0),
                false
        );
        schedule.addPeriod(
                StationDayOfWeek.SUNDAY,
                LocalTime.of(22, 0),
                LocalTime.of(2, 0),
                true
        );
        schedule.addPeriod(
                StationDayOfWeek.MONDAY,
                LocalTime.of(21, 0),
                LocalTime.of(1, 0),
                true
        );

        assertThat(resolver.resolveOperatingWindows(schedule, monday))
                .hasSize(2)
                .satisfiesExactly(
                        early -> {
                            assertThat(early.startAt())
                                    .isEqualTo(localInstant(2026, 9, 7, 0, 0));
                            assertThat(early.endAt())
                                    .isEqualTo(localInstant(2026, 9, 7, 2, 0));
                        },
                        late -> {
                            assertThat(late.startAt())
                                    .isEqualTo(localInstant(2026, 9, 7, 21, 0));
                            assertThat(late.endAt())
                                    .isEqualTo(localInstant(2026, 9, 8, 0, 0));
                        }
                );
    }

    private StationOperatingSchedule activeSchedule(Instant at, boolean open24Hours) {
        return StationOperatingSchedule.builder()
                .open24Hours(open24Hours)
                .effectiveFrom(at.minusSeconds(3600))
                .build();
    }

    private Instant localInstant(
            int year,
            int month,
            int day,
            int hour,
            int minute
    ) {
        return ZonedDateTime.of(
                year,
                month,
                day,
                hour,
                minute,
                0,
                0,
                SYSTEM_ZONE_ID
        ).toInstant();
    }
}
