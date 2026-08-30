package com.thang.chargeops.station.entity;

import com.thang.chargeops.common.enums.StationDayOfWeek;
import com.thang.chargeops.common.enums.TouRateDayType;
import com.thang.chargeops.common.enums.TouRatePeriodCode;
import com.thang.chargeops.station.exception.StationPricingDomainException;
import com.thang.chargeops.station.exception.violation.StationPricingViolation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StationPricingDomainTest {

    @Test
    void bookingSettingsOwnTheirDefaultsAndPricingMutation() {
        Station station = new Station();
        StationBookingSetting settings = StationBookingSetting.createDefault(station);

        settings.updatePricing(60, new BigDecimal("4100.00"));

        assertThat(settings.getStation()).isSameAs(station);
        assertThat(settings.getMinDurationMinutes()).isEqualTo(60);
        assertThat(settings.getDurationStepMinutes()).isEqualTo(30);
        assertThat(settings.getMaxDurationMinutes()).isEqualTo(180);
        assertThat(settings.getBasePriceVnd()).isEqualByComparingTo("4100.00");
        assertThatThrownBy(() -> settings.updatePricing(45, BigDecimal.ONE))
                .isInstanceOfSatisfying(
                        StationPricingDomainException.class,
                        exception -> assertThat(exception.getViolation())
                                .isEqualTo(StationPricingViolation.MIN_BOOKING_DURATION_INVALID)
                );
        assertThatThrownBy(() -> settings.updatePricing(60, BigDecimal.ZERO))
                .isInstanceOfSatisfying(
                        StationPricingDomainException.class,
                        exception -> assertThat(exception.getViolation())
                                .isEqualTo(StationPricingViolation.BASE_PRICE_INVALID)
                );
    }

    @Test
    void scheduleBuildsConsistentPeriodsAndExpiresItself() {
        Station station = new Station();
        Instant effectiveFrom = Instant.parse("2026-08-01T00:00:00Z");
        Instant expiresAt = Instant.parse("2026-08-30T00:00:00Z");
        StationOperatingSchedule schedule =
                StationOperatingSchedule.create(station, false, effectiveFrom);

        schedule.addPeriod(
                StationDayOfWeek.MONDAY,
                LocalTime.of(22, 0),
                LocalTime.of(2, 0),
                true
        );
        schedule.expireAt(expiresAt);

        assertThat(schedule.getPeriods()).hasSize(1);
        assertThat(schedule.getPeriods().getFirst().getSchedule()).isSameAs(schedule);
        assertThat(schedule.getEffectiveTo()).isEqualTo(expiresAt);
        assertThatThrownBy(() -> schedule.addPeriod(
                StationDayOfWeek.MONDAY,
                LocalTime.of(6, 0),
                LocalTime.of(23, 0),
                true
        )).isInstanceOfSatisfying(
                StationPricingDomainException.class,
                exception -> assertThat(exception.getViolation())
                        .isEqualTo(StationPricingViolation.OPERATING_WEEK_INVALID)
        );
    }

    @Test
    void open24HoursScheduleRejectsDailyPeriodsWithTypedViolation() {
        StationOperatingSchedule schedule = StationOperatingSchedule.create(
                new Station(),
                true,
                Instant.parse("2026-08-01T00:00:00Z")
        );

        assertThatThrownBy(() -> schedule.addPeriod(
                StationDayOfWeek.MONDAY,
                LocalTime.of(6, 0),
                LocalTime.of(23, 0),
                true
        )).isInstanceOfSatisfying(
                StationPricingDomainException.class,
                exception -> assertThat(exception.getViolation())
                        .isEqualTo(StationPricingViolation.OPEN_24_HOURS_PERIOD_NOT_ALLOWED)
        );
    }

    @Test
    void overnightTouRateCarriesItsDayTypeIntoTheFollowingDay() {
        TouRate weekendNight = TouRate.create(
                new Station(),
                " Weekend night ",
                TouRatePeriodCode.OFF_PEAK,
                TouRateDayType.WEEKEND,
                LocalTime.of(22, 0),
                LocalTime.of(2, 0),
                new BigDecimal("2800.00"),
                Instant.parse("2026-08-01T00:00:00Z")
        );

        assertThat(weekendNight.getName()).isEqualTo("Weekend night");
        assertThat(weekendNight.appliesAt(DayOfWeek.SUNDAY, LocalTime.of(23, 0))).isTrue();
        assertThat(weekendNight.appliesAt(DayOfWeek.MONDAY, LocalTime.of(1, 0))).isTrue();
        assertThat(weekendNight.appliesAt(DayOfWeek.MONDAY, LocalTime.of(3, 0))).isFalse();
    }

    @Test
    void touRateRejectsInvalidPricingDataWithTypedViolations() {
        Station station = new Station();
        Instant effectiveFrom = Instant.parse("2026-08-01T00:00:00Z");

        assertThatThrownBy(() -> TouRate.create(
                station,
                " ",
                TouRatePeriodCode.PEAK,
                TouRateDayType.DAILY,
                LocalTime.of(17, 0),
                LocalTime.of(21, 0),
                BigDecimal.ONE,
                effectiveFrom
        )).isInstanceOfSatisfying(
                StationPricingDomainException.class,
                exception -> assertThat(exception.getViolation())
                        .isEqualTo(StationPricingViolation.TOU_NAME_REQUIRED)
        );

        assertThatThrownBy(() -> TouRate.create(
                station,
                "Peak",
                TouRatePeriodCode.PEAK,
                TouRateDayType.DAILY,
                LocalTime.of(17, 0),
                LocalTime.of(17, 0),
                BigDecimal.ONE,
                effectiveFrom
        )).isInstanceOfSatisfying(
                StationPricingDomainException.class,
                exception -> assertThat(exception.getViolation())
                        .isEqualTo(StationPricingViolation.TOU_WINDOW_INVALID)
        );
    }
}
