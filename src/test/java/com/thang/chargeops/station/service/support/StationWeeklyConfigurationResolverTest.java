package com.thang.chargeops.station.service.support;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.enums.StationDayOfWeek;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.entity.StationOperatingSchedule;
import com.thang.chargeops.station.repository.StationOperatingScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StationWeeklyConfigurationResolverTest {

    private static final ZoneId SYSTEM_ZONE =
            ZoneId.of(SystemConstant.SYSTEM_REGION_TIMEZONE);

    @Mock
    private StationOperatingScheduleRepository scheduleRepository;

    private StationWeeklyConfigurationResolver resolver;

    @BeforeEach
    void setUp() {
        StationOperatingHoursResolver operatingHoursResolver =
                new StationOperatingHoursResolver(scheduleRepository);
        resolver = new StationWeeklyConfigurationResolver(
                scheduleRepository,
                operatingHoursResolver
        );
    }

    @Test
    void checksOperatingHoursOnBothSidesOfMondayVersionBoundary() {
        UUID stationId = UUID.randomUUID();
        Station station = new Station();
        Instant sundayStart = localInstant(LocalDate.of(2026, 8, 30), LocalTime.of(23, 0));
        Instant boundary = localInstant(LocalDate.of(2026, 8, 31), LocalTime.MIDNIGHT);
        Instant mondayOneAm = localInstant(LocalDate.of(2026, 8, 31), LocalTime.of(1, 0));
        Instant mondaySevenAm = localInstant(LocalDate.of(2026, 8, 31), LocalTime.of(7, 0));

        StationOperatingSchedule current = StationOperatingSchedule.create(
                station,
                true,
                localInstant(LocalDate.of(2026, 8, 24), LocalTime.MIDNIGHT),
                30,
                new BigDecimal("3200.00")
        );
        current.setEffectiveTo(boundary);
        StationOperatingSchedule pending = StationOperatingSchedule.create(
                station,
                false,
                boundary,
                60,
                new BigDecimal("3600.00")
        );
        for (StationDayOfWeek day : StationDayOfWeek.values()) {
            boolean monday = day == StationDayOfWeek.MONDAY;
            pending.addPeriod(
                    day,
                    monday ? LocalTime.MIDNIGHT : null,
                    monday ? LocalTime.of(6, 0) : null,
                    monday
            );
        }

        when(scheduleRepository.findEffectiveVersionsOverlapping(
                eq(stationId),
                any(Instant.class),
                any(Instant.class)
        )).thenReturn(List.of(current, pending));

        assertThat(resolver.coversInterval(stationId, sundayStart, mondayOneAm))
                .isTrue();
        assertThat(resolver.coversInterval(stationId, sundayStart, mondaySevenAm))
                .isFalse();
    }

    private Instant localInstant(LocalDate date, LocalTime time) {
        return date.atTime(time).atZone(SYSTEM_ZONE).toInstant();
    }
}
