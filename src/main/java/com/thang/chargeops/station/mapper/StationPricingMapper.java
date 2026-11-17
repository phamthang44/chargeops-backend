package com.thang.chargeops.station.mapper;

import com.thang.chargeops.common.enums.StationDayOfWeek;
import com.thang.chargeops.station.dto.station.response.StationPricingResponse;
import com.thang.chargeops.station.entity.StationBookingSetting;
import com.thang.chargeops.station.entity.StationOperatingPeriod;
import com.thang.chargeops.station.entity.StationOperatingSchedule;
import com.thang.chargeops.station.entity.TouRate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
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
        List<StationPricingResponse.TouRuleResponse> touRules = mapTouRules(rates);
        Integer scheduleMin = schedule != null ? schedule.getMinDurationMinutes() : null;
        BigDecimal scheduleBasePrice = schedule != null ? schedule.getBasePriceVnd() : null;

        return new StationPricingResponse(
                stationId,
                scheduleMin != null
                        ? scheduleMin
                        : settings != null
                                ? settings.getMinDurationMinutes()
                                : StationBookingSetting.DEFAULT_MIN_DURATION_MINUTES,
                StationBookingSetting.DEFAULT_DURATION_STEP_MINUTES,
                StationBookingSetting.DEFAULT_MAX_DURATION_MINUTES,
                scheduleBasePrice != null
                        ? scheduleBasePrice
                        : settings != null ? settings.getBasePriceVnd() : null,
                schedule != null && schedule.isOpen24Hours(),
                operatingHours(schedule),
                touRules,
                new StationPricingResponse.AvailabilityPolicyResponse(
                        true,
                        NO_SHOW_TIMEOUT_MINUTES,
                        MAX_ADVANCE_CALENDAR_DAYS,
                        TURNAROUND_BUFFER_MINUTES
                ),
                schedule != null ? schedule.getEffectiveFrom() : null,
                schedule != null ? schedule.getEffectiveTo() : null,
                settings == null && schedule == null
                        ? "UNCONFIGURED"
                        : (schedule != null ? "ACTIVE" : "DEFAULT"),
                settings != null ? settings.getVersion() + 1L : 0L,
                null
        );
    }

    public StationPricingResponse toOwnerResponse(
            StationPricingResponse current,
            StationOperatingSchedule pending,
            List<TouRate> pendingRates
    ) {
        return new StationPricingResponse(
                current.stationId(),
                current.minBookingDurationMin(),
                current.durationStepMinutes(),
                current.maxDurationMinutes(),
                current.basePriceVnd(),
                current.open24Hours(),
                current.hours(),
                current.touRules(),
                current.availability(),
                current.scheduleEffectiveFrom(),
                current.scheduleEffectiveTo(),
                current.scheduleStatus(),
                current.version(),
                pending == null ? null : toConfigurationVersion(pending, pendingRates)
        );
    }

    public StationPricingResponse.ConfigurationVersionResponse toConfigurationVersion(
            StationOperatingSchedule schedule,
            List<TouRate> rates
    ) {
        return new StationPricingResponse.ConfigurationVersionResponse(
                schedule.getId(),
                schedule.getMinDurationMinutes() != null
                        ? schedule.getMinDurationMinutes()
                        : StationBookingSetting.DEFAULT_MIN_DURATION_MINUTES,
                schedule.getBasePriceVnd(),
                schedule.isOpen24Hours(),
                operatingHours(schedule),
                mapTouRules(rates),
                schedule.getEffectiveFrom(),
                schedule.getEffectiveTo(),
                schedule.getCancelledAt() == null ? "PENDING" : "CANCELLED",
                schedule.getCreatedAt() != null
                        ? schedule.getCreatedAt()
                        : schedule.getEffectiveFrom()
        );
    }

    public List<StationPricingResponse.TouRuleResponse> mapTouRules(List<TouRate> rates) {
        return rates.stream()
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
    }

    public List<StationPricingResponse.OperatingHourResponse> operatingHours(
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
