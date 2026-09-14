package com.thang.chargeops.booking.policy;

import com.thang.chargeops.booking.config.BookingPolicyConfig;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.BookingErrorCode;
import com.thang.chargeops.station.entity.StationOperatingSchedule;
import com.thang.chargeops.station.service.model.OperatingWindow;
import com.thang.chargeops.station.service.support.StationOperatingHoursResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;

/**
 * BKG-012: shared rules for a new booking window, not for paying an existing hold.
 * The caller supplies one server time from Clock (after locking when creating a booking).
 * Calendar coverage does not imply connector availability; overlap is checked separately.
 */
@Component
@RequiredArgsConstructor
public class BookingTimePolicy {

    private static final ZoneId ZONE = ZoneId.of(BookingPolicyConfig.TIMEZONE);

    private final BookingPolicyConfig config;
    private final StationOperatingHoursResolver operatingHoursResolver;

    /** Temporal lower bound only; does not promise that the station is open or the slot is free. */
    public Instant earliestStartAt(Instant now) {
        return ceilToGrid(Objects.requireNonNull(now),
                config.getMinimumAdvanceMinutes(), config.getOperatingGridMinutes());
    }

    /** Kiểm tra ngày yêu cầu bắt đầu sạc có thuộc Today/Tomorrow hay không (BR-BOK-10). */
    public void validateBookingDate(LocalDate queryDate, Instant now) {
        validateDateOffset("date", queryDate, now);
    }

    private void validateDateOffset(String field, LocalDate queryDate, Instant now) {
        Objects.requireNonNull(queryDate, "Query date is required");
        Objects.requireNonNull(now, "Server time is required");
        LocalDate today = now.atZone(ZONE).toLocalDate();
        long dayOffset = ChronoUnit.DAYS.between(today, queryDate);
        if (dayOffset < 0 || dayOffset >= config.getAdvanceBookingDays()) {
            Instant earliest = earliestStartAt(now);
            throw invalid(field, "START_DATE_OUT_OF_RANGE", earliest);
        }
    }

    /** Validates [startAt, endAt) against the current schedule and returns its exclusive end. */
    public Instant validate(
            Instant startAt, int durationMin, int stationMinDurationMin,
            StationOperatingSchedule schedule, Instant now
    ) {
        Objects.requireNonNull(now, "Server time is required");
        int grid = config.getOperatingGridMinutes();
        Instant earliest = ceilToGrid(now, config.getMinimumAdvanceMinutes(), grid);
        if (startAt == null) {
            throw invalid("startAt", "START_REQUIRED", earliest);
        }

        ZonedDateTime localStart = startAt.atZone(ZONE);
        validateDateOffset("startAt", localStart.toLocalDate(), now);
        if (localStart.getSecond() != 0 || localStart.getNano() != 0
                || localStart.toLocalTime().toSecondOfDay() / 60 % grid != 0) {
            throw invalid("startAt", "START_NOT_ON_GRID", earliest);
        }
        if (startAt.isBefore(earliest)) {
            throw invalid("startAt", "MINIMUM_ADVANCE_NOT_MET", earliest);
        }

        int min = Math.max(config.getMinDurationMinutes(), stationMinDurationMin);
        int max = config.getMaxDurationMinutes();
        int step = config.getDurationStepMinutes();
        if (durationMin < min || durationMin > max || durationMin % step != 0) {
            throw AppException.withDetails(BookingErrorCode.TIME_INVALID, Map.of(
                    "field", "durationMin", "reason", "DURATION_OUT_OF_RANGE",
                    "minDurationMinutes", min, "maxDurationMinutes", max,
                    "durationStepMinutes", step));
        }

        Instant endAt = startAt.plus(Duration.ofMinutes(durationMin));
        if (schedule == null || !schedule.isActive(now)) {
            throw invalid("startAt", "OPERATING_SCHEDULE_NOT_CONFIGURED", earliest);
        }
        // Do not extend a known expiring schedule beyond its effective interval.
        if (!schedule.isActive(startAt)
                || (schedule.getEffectiveTo() != null && schedule.getEffectiveTo().isBefore(endAt))) {
            throw invalid("startAt", "OUTSIDE_OPERATING_HOURS", earliest);
        }
        if (!operatingHoursResolver.coversInterval(schedule, startAt, endAt)) {
            throw invalid("startAt", "OUTSIDE_OPERATING_HOURS", earliest);
        }
        return endAt;
    }

    private Instant ceilToGrid(Instant now, int leadMinutes, int gridMinutes) {
        ZonedDateTime threshold = now.plus(Duration.ofMinutes(leadMinutes)).atZone(ZONE);
        int minuteOfDay = threshold.getHour() * 60 + threshold.getMinute();
        ZonedDateTime floor = threshold.truncatedTo(ChronoUnit.MINUTES)
                .minusMinutes(minuteOfDay % gridMinutes);
        return (floor.isBefore(threshold) ? floor.plusMinutes(gridMinutes) : floor).toInstant();
    }

    private AppException invalid(String field, String reason, Instant earliest) {
        return AppException.withDetails(BookingErrorCode.TIME_INVALID, Map.of(
                "field", field, "reason", reason, "earliestStartAt", earliest));
    }
}
