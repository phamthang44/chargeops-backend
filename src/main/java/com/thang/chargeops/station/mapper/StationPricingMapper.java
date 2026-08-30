package com.thang.chargeops.station.mapper;

import com.thang.chargeops.common.enums.StationDayOfWeek;
import com.thang.chargeops.station.dto.station.response.StationPricingResponse;
import com.thang.chargeops.station.entity.StationBookingSetting;
import com.thang.chargeops.station.entity.StationOperatingPeriod;
import com.thang.chargeops.station.entity.StationOperatingSchedule;
import com.thang.chargeops.station.entity.TouRate;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class StationPricingMapper {

    private static final int NO_SHOW_TIMEOUT_MINUTES = 15;
    private static final int MAX_ADVANCE_CALENDAR_DAYS = 2;
    private static final int TURNAROUND_BUFFER_MINUTES = 10;
    private static final LocalTime DEFAULT_OPEN_TIME = LocalTime.of(6, 0);
    private static final LocalTime DEFAULT_CLOSE_TIME = LocalTime.of(23, 0);

    public StationPricingResponse toResponse(
            UUID stationId,
            StationBookingSetting settings,
            StationOperatingSchedule schedule,
            List<TouRate> rates
    ) {
        List<StationPricingResponse.TouRuleResponse> touRules = rates.stream()
                .sorted(Comparator.comparing(TouRate::getDayType)
                        .thenComparing(TouRate::getStartTime))
                .map(rate -> new StationPricingResponse.TouRuleResponse(
                        rate.getId(),
                        rate.getName(),
                        rate.getPeriodCode(),
                        rate.getDayType(),
                        rate.getStartTime(),
                        rate.getEndTime(),
                        rate.getPricePerKwh()
                ))
                .toList();

        return new StationPricingResponse(
                stationId,
                settings.getMinDurationMinutes(),
                StationBookingSetting.DEFAULT_DURATION_STEP_MINUTES,
                StationBookingSetting.DEFAULT_MAX_DURATION_MINUTES,
                settings.getBasePriceVnd(),
                schedule != null && schedule.isOpen24Hours(),
                operatingHours(schedule),
                touRules,
                new StationPricingResponse.AvailabilityPolicyResponse(
                        true,
                        NO_SHOW_TIMEOUT_MINUTES,
                        MAX_ADVANCE_CALENDAR_DAYS,
                        TURNAROUND_BUFFER_MINUTES
                )
        );
    }

    private List<StationPricingResponse.OperatingHourResponse> operatingHours(
            StationOperatingSchedule schedule
    ) {
        if (schedule == null) {
            return Arrays.stream(StationDayOfWeek.values())
                    .map(day -> new StationPricingResponse.OperatingHourResponse(
                            day,
                            DEFAULT_OPEN_TIME,
                            DEFAULT_CLOSE_TIME,
                            true
                    ))
                    .toList();
        }
        if (schedule.isOpen24Hours()) {
            return Arrays.stream(StationDayOfWeek.values())
                    .map(day -> new StationPricingResponse.OperatingHourResponse(
                            day,
                            null,
                            null,
                            true
                    ))
                    .toList();
        }

        Map<StationDayOfWeek, StationOperatingPeriod> byDay =
                new EnumMap<>(StationDayOfWeek.class);
        schedule.getPeriods().forEach(period -> byDay.put(period.getDayOfWeek(), period));
        return Arrays.stream(StationDayOfWeek.values())
                .map(day -> {
                    StationOperatingPeriod period = byDay.get(day);
                    return new StationPricingResponse.OperatingHourResponse(
                            day,
                            period == null ? null : period.getOpenTime(),
                            period == null ? null : period.getCloseTime(),
                            period != null && period.isEnabled()
                    );
                })
                .toList();
    }
}
