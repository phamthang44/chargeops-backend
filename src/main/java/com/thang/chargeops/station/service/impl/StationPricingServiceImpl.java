package com.thang.chargeops.station.service.impl;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.enums.StationDayOfWeek;
import com.thang.chargeops.common.enums.TouRateDayType;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.dto.station.request.UpdateStationPricingRequest;
import com.thang.chargeops.station.dto.station.response.StationPricingResponse;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.entity.StationBookingSettings;
import com.thang.chargeops.station.entity.StationOperatingPeriod;
import com.thang.chargeops.station.entity.StationOperatingSchedule;
import com.thang.chargeops.station.entity.TouRate;
import com.thang.chargeops.station.policy.StationPricingPolicy;
import com.thang.chargeops.station.repository.*;
import com.thang.chargeops.station.service.StationPricingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class StationPricingServiceImpl implements StationPricingService {

    static final int DURATION_STEP_MINUTES = 30;
    static final int MAX_DURATION_MINUTES = 180;
    static final int NO_SHOW_TIMEOUT_MINUTES = 15;
    static final int MAX_ADVANCE_CALENDAR_DAYS = 2;
    static final int TURNAROUND_BUFFER_MINUTES = 10;
    static final int DEFAULT_MIN_DURATION_MINUTES = 30;
    static final BigDecimal DEFAULT_BASE_PRICE_VND = new BigDecimal("3400.00");
    static final LocalTime DEFAULT_OPEN_TIME = LocalTime.of(6, 0);
    static final LocalTime DEFAULT_CLOSE_TIME = LocalTime.of(23, 0);

    private final StationRepository stationRepository;
    private final StationBookingSettingsRepository stationBookingSettingsRepository;
    private final StationOperatingScheduleRepository stationOperatingScheduleRepository;
    private final TouRateRepository touRateRepository;
    private final CurrentProfileProvider currentProfileProvider;
    private final StationPricingPolicy stationPricingPolicy;

    @Override
    @Transactional(readOnly = true)
    public StationPricingResponse getStationPricing(UUID stationId) {
        Station station = requireStation(stationId);
        stationPricingPolicy.validateStationOwnershipAndStatus(
                station,
                currentProfileProvider.requireProfileId()
        );
        return buildResponse(station, Instant.now());
    }

    @Override
    @Transactional
    public StationPricingResponse updateStationPricing(UUID stationId, UpdateStationPricingRequest request) {
        Station station = stationRepository.findByIdForPricingUpdate(stationId)
                .orElseThrow(() -> new AppException(StationErrorCode.STATION_NOT_FOUND, stationId));
        stationPricingPolicy.validateStationOwnershipAndStatus(
                station,
                currentProfileProvider.requireProfileId()
        );
        stationPricingPolicy.validateBookingSettings(request.minBookingDurationMin());
        stationPricingPolicy.validateOperatingHours(request.open24Hours(), request.hours());
        stationPricingPolicy.validateTouRules(request.touRules());

        Instant effectiveAt = Instant.now();
        StationBookingSettings settings = stationBookingSettingsRepository.findByStationId(stationId)
                .orElseGet(() -> StationBookingSettings.builder()
                        .station(station)
                        .durationStepMinutes(DURATION_STEP_MINUTES)
                        .maxDurationMinutes(MAX_DURATION_MINUTES)
                        .build());
        settings.setMinDurationMinutes(request.minBookingDurationMin());
        settings.setDurationStepMinutes(DURATION_STEP_MINUTES);
        settings.setMaxDurationMinutes(MAX_DURATION_MINUTES);
        settings.setBasePriceVnd(request.basePriceVnd());
        stationBookingSettingsRepository.save(settings);

        stationOperatingScheduleRepository.findActiveByStationId(stationId, effectiveAt)
                .ifPresent(active -> active.setEffectiveTo(effectiveAt));
        stationOperatingScheduleRepository.flush();

        StationOperatingSchedule schedule = StationOperatingSchedule.builder()
                .station(station)
                .open24Hours(request.open24Hours())
                .effectiveFrom(effectiveAt)
                .build();
        if (!request.open24Hours()) {
            request.hours().forEach(hour -> schedule.addPeriod(
                    StationOperatingPeriod.builder()
                            .dayOfWeek(hour.day())
                            .openTime(hour.enabled() ? hour.openTime() : null)
                            .closeTime(hour.enabled() ? hour.closeTime() : null)
                            .enabled(hour.enabled())
                            .build()
            ));
        }
        stationOperatingScheduleRepository.save(schedule);

        List<TouRate> previousRates = touRateRepository.findActiveByStationId(stationId, effectiveAt);
        previousRates.forEach(rate -> rate.setEffectiveTo(effectiveAt));
        touRateRepository.saveAll(previousRates);

        List<TouRate> newRates = request.touRules().stream()
                .map(rule -> TouRate.builder()
                        .station(station)
                        .name(rule.name().trim())
                        .periodCode(rule.periodCode())
                        .dayType(rule.dayType())
                        .startTime(rule.startTime())
                        .endTime(rule.endTime())
                        .pricePerKwh(rule.rateVnd())
                        .effectiveFrom(effectiveAt)
                        .build())
                .toList();
        touRateRepository.saveAll(newRates);

        log.info("Updated pricing configuration for station {}", stationId);
        return toResponse(stationId, settings, schedule, newRates);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal resolvePriceAt(UUID stationId, Instant targetTime) {
        requireStation(stationId);
        BigDecimal basePrice = stationBookingSettingsRepository.findByStationId(stationId)
                .map(StationBookingSettings::getBasePriceVnd)
                .orElse(DEFAULT_BASE_PRICE_VND);

        ZonedDateTime local = targetTime.atZone(
                ZoneId.of(SystemConstant.SYSTEM_REGION_TIMEZONE)
        );
        return touRateRepository.findActiveByStationId(stationId, targetTime).stream()
                .filter(rate -> matches(rate, local.getDayOfWeek(), local.toLocalTime()))
                .map(TouRate::getPricePerKwh)
                .findFirst()
                .orElse(basePrice);
    }

    private StationPricingResponse buildResponse(Station station, Instant at) {
        StationBookingSettings settings = stationBookingSettingsRepository.findByStationId(station.getId())
                .orElseGet(() -> StationBookingSettings.builder()
                        .station(station)
                        .minDurationMinutes(DEFAULT_MIN_DURATION_MINUTES)
                        .durationStepMinutes(DURATION_STEP_MINUTES)
                        .maxDurationMinutes(MAX_DURATION_MINUTES)
                        .basePriceVnd(DEFAULT_BASE_PRICE_VND)
                        .build());
        StationOperatingSchedule schedule = stationOperatingScheduleRepository
                .findActiveByStationId(station.getId(), at)
                .orElse(null);
        List<TouRate> rates = touRateRepository.findActiveByStationId(station.getId(), at);
        return toResponse(station.getId(), settings, schedule, rates);
    }

    private StationPricingResponse toResponse(
            UUID stationId,
            StationBookingSettings settings,
            StationOperatingSchedule schedule,
            List<TouRate> rates
    ) {
        List<StationPricingResponse.OperatingHourResponse> hours = operatingHours(schedule);
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
                DURATION_STEP_MINUTES,
                MAX_DURATION_MINUTES,
                settings.getBasePriceVnd(),
                schedule != null && schedule.isOpen24Hours(),
                hours,
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

    private boolean matches(TouRate rate, DayOfWeek currentDay, LocalTime currentTime) {
        LocalTime start = rate.getStartTime();
        LocalTime end = rate.getEndTime();
        if (start.isBefore(end)) {
            return appliesOn(rate.getDayType(), currentDay)
                    && !currentTime.isBefore(start)
                    && currentTime.isBefore(end);
        }
        if (!currentTime.isBefore(start)) {
            return appliesOn(rate.getDayType(), currentDay);
        }
        return currentTime.isBefore(end)
                && appliesOn(rate.getDayType(), currentDay.minus(1));
    }

    private boolean appliesOn(TouRateDayType dayType, DayOfWeek day) {
        return switch (dayType) {
            case DAILY -> true;
            case WEEKDAY -> day.getValue() <= DayOfWeek.FRIDAY.getValue();
            case WEEKEND -> day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
        };
    }

    private Station requireStation(UUID stationId) {
        return stationRepository.findById(stationId)
                .orElseThrow(() -> new AppException(
                        StationErrorCode.STATION_NOT_FOUND,
                        stationId
                ));
    }
}
