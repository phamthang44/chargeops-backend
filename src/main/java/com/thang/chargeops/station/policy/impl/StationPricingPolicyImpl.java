package com.thang.chargeops.station.policy.impl;

import com.thang.chargeops.common.enums.StationDayOfWeek;
import com.thang.chargeops.common.enums.TouRateDayType;
import com.thang.chargeops.exception.AppException;

import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.station.dto.station.request.UpdateStationPricingRequest;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.policy.StationPricingPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class StationPricingPolicyImpl implements StationPricingPolicy {

    @Override
    public void validateStationOwnershipAndStatus(Station station, UUID currentOwnerId) {
        if (!station.getOwner().getId().equals(currentOwnerId)) {
            throw new AppException(StationErrorCode.STATION_ACCESS_DENIED);
        }
        // Station/License eligibility gates Driver discovery and new bookings,
        // not the Owner's ability to maintain configuration for an owned Station.
    }

    @Override
    public void validateBookingSettings(int minDurationMinutes) {
        if (minDurationMinutes != 30 && minDurationMinutes != 60 && minDurationMinutes != 90) {
            throw new IllegalArgumentException(
                    "Minimum booking duration must be exactly 30, 60, or 90 minutes"
            );
        }
    }

    @Override
    public void validateOperatingHours(boolean open24Hours, List<UpdateStationPricingRequest.OperatingHourRequest> hours) {
        if (hours == null || hours.size() != StationDayOfWeek.values().length) {
            throw new IllegalArgumentException("Operating hours must contain exactly seven days");
        }

        Set<StationDayOfWeek> uniqueDays = EnumSet.noneOf(StationDayOfWeek.class);
        for (var hour : hours) {
            if (hour.day() == null || !uniqueDays.add(hour.day())) {
                throw new IllegalArgumentException("Operating hours must contain every day exactly once");
            }
            if (open24Hours) {
                continue;
            }
            if (!hour.enabled()) {
                if (hour.openTime() != null || hour.closeTime() != null) {
                    throw new IllegalArgumentException(
                            "A closed day must not contain open or close time"
                    );
                }
                continue;
            }
            if (hour.openTime() == null || hour.closeTime() == null) {
                throw new IllegalArgumentException(
                        "An enabled day requires both open and close time"
                );
            }
            if (hour.openTime().equals(hour.closeTime())) {
                throw new IllegalArgumentException(
                        "Equal open and close time is ambiguous; use the 24/7 option instead"
                );
            }
            // close < open is intentionally valid and means the window crosses midnight.
        }
    }

    @Override
    public void validateTouRules(List<UpdateStationPricingRequest.TouRuleRequest> touRules) {
        if (touRules == null) {
            throw new IllegalArgumentException(
                    "TOU rules are required; use an empty list when only base price applies"
            );
        }

        Set<String> names = new HashSet<>();
        List<WeeklyInterval> occupied = new ArrayList<>();
        for (int ruleIndex = 0; ruleIndex < touRules.size(); ruleIndex++) {
            var rule = touRules.get(ruleIndex);
            String normalizedName = rule.name().trim().toLowerCase(Locale.ROOT);
            if (!names.add(normalizedName)) {
                throw new IllegalArgumentException("TOU rule names must be unique");
            }
            if (rule.startTime().equals(rule.endTime())) {
                throw new IllegalArgumentException("A TOU rule must have a non-zero time window");
            }
            if (rule.rateVnd().signum() <= 0) {
                throw new IllegalArgumentException("TOU rate must be greater than zero");
            }

            for (WeeklyInterval candidate : expand(
                    ruleIndex,
                    rule.dayType(),
                    rule.startTime(),
                    rule.endTime()
            )) {
                boolean overlaps = occupied.stream()
                        .anyMatch(existing -> existing.ruleIndex() != candidate.ruleIndex()
                                && existing.startMinute() < candidate.endMinute()
                                && candidate.startMinute() < existing.endMinute());
                if (overlaps) {
                    throw new IllegalArgumentException(
                            "TOU rules overlap after applying DAILY/WEEKDAY/WEEKEND day groups"
                    );
                }
                occupied.add(candidate);
            }
        }
    }

    private List<WeeklyInterval> expand(
            int ruleIndex,
            TouRateDayType dayType,
            LocalTime start,
            LocalTime end
    ) {
        List<WeeklyInterval> result = new ArrayList<>();
        int startMinute = start.getHour() * 60 + start.getMinute();
        int endMinute = end.getHour() * 60 + end.getMinute();
        boolean overnight = endMinute < startMinute;

        for (DayOfWeek day : applicableDays(dayType)) {
            int dayStart = (day.getValue() - 1) * 24 * 60;
            int absoluteStart = dayStart + startMinute;
            int absoluteEnd = dayStart + endMinute + (overnight ? 24 * 60 : 0);
            if (absoluteEnd <= 7 * 24 * 60) {
                result.add(new WeeklyInterval(ruleIndex, absoluteStart, absoluteEnd));
            } else {
                result.add(new WeeklyInterval(ruleIndex, absoluteStart, 7 * 24 * 60));
                result.add(new WeeklyInterval(ruleIndex, 0, absoluteEnd - 7 * 24 * 60));
            }
        }
        return result;
    }

    private EnumSet<DayOfWeek> applicableDays(TouRateDayType dayType) {
        return switch (dayType) {
            case DAILY -> EnumSet.allOf(DayOfWeek.class);
            case WEEKDAY -> EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
            case WEEKEND -> EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
        };
    }

    private record WeeklyInterval(int ruleIndex, int startMinute, int endMinute) {}
}
