package com.thang.chargeops.booking.policy;

import com.thang.chargeops.booking.config.BookingPolicyConfig;
import com.thang.chargeops.common.enums.StationDayOfWeek;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.BookingErrorCode;
import com.thang.chargeops.station.entity.StationOperatingSchedule;
import com.thang.chargeops.station.service.support.StationOperatingHoursResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookingTimePolicyTest {
    private static final Instant NOW = at("2026-09-06T09:07:00+07:00");
    private final BookingTimePolicy policy = new BookingTimePolicy(
            BookingPolicyConfig.defaults(), new StationOperatingHoursResolver(null));

    @ParameterizedTest
    @CsvSource({
            "2026-09-06T09:07:00+07:00,2026-09-06T10:30:00+07:00",
            "2026-09-06T09:00:00+07:00,2026-09-06T10:00:00+07:00",
            "2026-09-06T09:30:00+07:00,2026-09-06T10:30:00+07:00",
            "2026-09-06T09:00:00.000000001+07:00,2026-09-06T10:30:00+07:00",
            "2026-09-06T23:07:00+07:00,2026-09-07T00:30:00+07:00"
    })
    void earliestStartCeilsLeadTimeWithoutAddingAnExtraGrid(String now, String expected) {
        assertThat(policy.earliestStartAt(at(now))).isEqualTo(at(expected));
    }

    @Test
    void acceptsExactlySixtyMinutesButRejectsOneSecondLess() {
        Instant start = at("2026-09-06T11:00:00+07:00");
        assertThat(policy.validate(start, 60, 30, alwaysOpen(), start.minusSeconds(3600)))
                .isEqualTo(start.plusSeconds(3600));
        assertInvalid(start, 60, 30, alwaysOpen(), start.minusSeconds(3599),
                "startAt", "MINIMUM_ADVANCE_NOT_MET");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-09-06T11:15:00+07:00", "2026-09-06T11:00:01+07:00",
            "2026-09-06T11:00:00.000000001+07:00"})
    void rejectsOffGridMinutesSecondsAndNanos(String start) {
        assertInvalid(at(start), 60, 30, alwaysOpen(), NOW, "startAt", "START_NOT_ON_GRID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-09-05T23:30:00+07:00", "2026-09-08T00:00:00+07:00"})
    void rejectsStartOutsideTodayAndTomorrow(String start) {
        assertInvalid(at(start), 60, 30, alwaysOpen(), NOW, "startAt", "START_DATE_OUT_OF_RANGE");
    }

    @Test
    void usesVietnameseCalendarEvenWhenUtcDateIsDifferent() {
        Instant now = at("2026-09-07T00:07:00+07:00");
        Instant start = at("2026-09-08T23:30:00+07:00");
        assertThat(policy.validate(start, 60, 30, alwaysOpen(), now))
                .isEqualTo(at("2026-09-09T00:30:00+07:00"));
    }

    @ParameterizedTest
    @ValueSource(ints = {-30, 0, 29, 31, 45, 181, 210, Integer.MAX_VALUE})
    void rejectsInvalidDurationsBeforeDateArithmetic(int duration) {
        assertInvalid(at("2026-09-06T11:00:00+07:00"), duration, 30, alwaysOpen(), NOW,
                "durationMin", "DURATION_OUT_OF_RANGE");
    }

    @ParameterizedTest
    @ValueSource(ints = {30, 60, 180})
    void acceptsPlatformDurationBoundaries(int duration) {
        Instant start = at("2026-09-06T11:00:00+07:00");
        assertThat(policy.validate(start, duration, 30, alwaysOpen(), NOW))
                .isEqualTo(start.plusSeconds(duration * 60L));
    }

    @Test
    void enforcesOwnerMinimumWithoutLoweringPlatformFloor() {
        Instant start = at("2026-09-06T11:00:00+07:00");
        assertInvalid(start, 30, 60, alwaysOpen(), NOW, "durationMin", "DURATION_OUT_OF_RANGE");
        assertThat(policy.validate(start, 60, 60, alwaysOpen(), NOW)).isEqualTo(start.plusSeconds(3600));
        assertInvalid(start, 0, 0, alwaysOpen(), NOW, "durationMin", "DURATION_OUT_OF_RANGE");
    }

    @Test
    void allowsTomorrowSessionToEndOnTheFollowingDay() {
        Instant start = at("2026-09-07T23:30:00+07:00");
        assertThat(policy.validate(start, 180, 30, alwaysOpen(), NOW))
                .isEqualTo(at("2026-09-08T02:30:00+07:00"));
    }

    @Test
    void allowsContinuousOvernightScheduleAcrossWeekBoundary() {
        StationOperatingSchedule schedule = schedule(false);
        schedule.addPeriod(StationDayOfWeek.SUNDAY, LocalTime.of(22, 0), LocalTime.of(2, 0), true);
        Instant start = at("2026-09-06T23:30:00+07:00");
        assertThat(policy.validate(start, 150, 30, schedule, NOW))
                .isEqualTo(at("2026-09-07T02:00:00+07:00"));
    }

    @Test
    void rejectsClosedGapEvenThoughBothEndpointsAreInsideOperatingWindows() {
        StationOperatingSchedule schedule = splitOvernightSchedule(LocalTime.of(1, 0));
        assertInvalid(at("2026-09-06T23:30:00+07:00"), 120, 30, schedule, NOW,
                "startAt", "OUTSIDE_OPERATING_HOURS");
    }

    @Test
    void mergesTouchingWindowsAcrossDaysWithoutInventingABuffer() {
        StationOperatingSchedule schedule = splitOvernightSchedule(LocalTime.of(0, 30));
        assertThat(policy.validate(at("2026-09-06T23:30:00+07:00"), 120, 30, schedule, NOW))
                .isEqualTo(at("2026-09-07T01:30:00+07:00"));
    }

    @Test
    void allowsExactClosingEndButRejectsStartAtClosingOrPastClosingEnd() {
        StationOperatingSchedule schedule = schedule(false);
        schedule.addPeriod(StationDayOfWeek.SUNDAY, LocalTime.of(11, 0), LocalTime.of(12, 0), true);
        assertThat(policy.validate(at("2026-09-06T11:00:00+07:00"), 60, 30, schedule, NOW))
                .isEqualTo(at("2026-09-06T12:00:00+07:00"));
        assertInvalid(at("2026-09-06T12:00:00+07:00"), 30, 30, schedule, NOW,
                "startAt", "OUTSIDE_OPERATING_HOURS");
        assertInvalid(at("2026-09-06T11:30:00+07:00"), 60, 30, schedule, NOW,
                "startAt", "OUTSIDE_OPERATING_HOURS");
    }

    @Test
    void midnightEndDoesNotRequireNextDayToBeOpen() {
        StationOperatingSchedule schedule = schedule(false);
        schedule.addPeriod(StationDayOfWeek.SUNDAY, LocalTime.of(22, 0), LocalTime.MIDNIGHT, true);
        assertThat(policy.validate(at("2026-09-06T23:00:00+07:00"), 60, 30, schedule, NOW))
                .isEqualTo(at("2026-09-07T00:00:00+07:00"));
        assertInvalid(at("2026-09-06T23:00:00+07:00"), 90, 30, schedule, NOW,
                "startAt", "OUTSIDE_OPERATING_HOURS");
    }

    @Test
    void rejectsMissingInactiveOrDisabledSchedule() {
        Instant start = at("2026-09-06T11:00:00+07:00");
        assertInvalid(start, 60, 30, null, NOW, "startAt", "OPERATING_SCHEDULE_NOT_CONFIGURED");
        StationOperatingSchedule expired = alwaysOpen();
        expired.expireAt(NOW);
        assertInvalid(start, 60, 30, expired, NOW, "startAt", "OPERATING_SCHEDULE_NOT_CONFIGURED");
        StationOperatingSchedule future = alwaysOpen();
        future.setEffectiveFrom(NOW.plusSeconds(60));
        assertInvalid(start, 60, 30, future, NOW, "startAt", "OPERATING_SCHEDULE_NOT_CONFIGURED");
        StationOperatingSchedule closed = schedule(false);
        closed.addPeriod(StationDayOfWeek.SUNDAY, null, null, false);
        assertInvalid(start, 60, 30, closed, NOW, "startAt", "OUTSIDE_OPERATING_HOURS");
    }

    @Test
    void doesNotSellBeyondKnownScheduleExpiry() {
        Instant start = at("2026-09-06T11:00:00+07:00");
        StationOperatingSchedule schedule = alwaysOpen();
        schedule.expireAt(start.plusSeconds(3600));
        assertThat(policy.validate(start, 60, 30, schedule, NOW)).isEqualTo(start.plusSeconds(3600));
        assertInvalid(start, 90, 30, schedule, NOW, "startAt", "OUTSIDE_OPERATING_HOURS");
    }

    @Test
    void missingStartHasStructuredErrorAndEarliestStart() {
        assertInvalid(null, 60, 30, alwaysOpen(), NOW, "startAt", "START_REQUIRED");
    }

    @Test
    void takesTemporalRulesFromSystemConfig() {
        BookingPolicyConfig custom = new BookingPolicyConfig(null) {
            @Override public int getMinimumAdvanceMinutes() { return 90; }
            @Override public int getOperatingGridMinutes() { return 60; }
            @Override public int getAdvanceBookingDays() { return 1; }
        };
        BookingTimePolicy configured = new BookingTimePolicy(custom, new StationOperatingHoursResolver(null));
        assertThat(configured.earliestStartAt(NOW)).isEqualTo(at("2026-09-06T11:00:00+07:00"));
        assertThatThrownBy(() -> configured.validate(at("2026-09-07T11:00:00+07:00"), 60, 30,
                alwaysOpen(), NOW)).isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(((Map<?, ?>) exception.getDetails()).get("reason"))
                        .isEqualTo("START_DATE_OUT_OF_RANGE"));
    }

    private void assertInvalid(Instant start, int duration, int min, StationOperatingSchedule schedule,
                               Instant now, String field, String reason) {
        assertThatThrownBy(() -> policy.validate(start, duration, min, schedule, now))
                .isInstanceOfSatisfying(AppException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(BookingErrorCode.TIME_INVALID);
                    Map<?, ?> details = (Map<?, ?>) exception.getDetails();
                    assertThat(details.get("field")).isEqualTo(field);
                    assertThat(details.get("reason")).isEqualTo(reason);
                    if (field.equals("startAt")) {
                        assertThat(details.get("earliestStartAt")).isEqualTo(policy.earliestStartAt(now));
                    } else {
                        assertThat(details.get("minDurationMinutes")).isEqualTo(Math.max(30, min));
                        assertThat(details.get("maxDurationMinutes")).isEqualTo(180);
                        assertThat(details.get("durationStepMinutes")).isEqualTo(30);
                    }
                });
    }

    private StationOperatingSchedule splitOvernightSchedule(LocalTime mondayOpening) {
        StationOperatingSchedule schedule = schedule(false);
        schedule.addPeriod(StationDayOfWeek.SUNDAY, LocalTime.of(22, 0), LocalTime.of(0, 30), true);
        schedule.addPeriod(StationDayOfWeek.MONDAY, mondayOpening, LocalTime.of(5, 0), true);
        return schedule;
    }

    private StationOperatingSchedule alwaysOpen() { return schedule(true); }

    private StationOperatingSchedule schedule(boolean open24Hours) {
        return StationOperatingSchedule.builder().open24Hours(open24Hours)
                .effectiveFrom(NOW.minusSeconds(86400)).build();
    }

    private static Instant at(String value) { return OffsetDateTime.parse(value).toInstant(); }
}
