package com.thang.chargeops.station.service.impl;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.enums.TouRatePeriodCode;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.dto.station.request.UpdateStationPricingRequest;
import com.thang.chargeops.station.dto.station.response.StationPricingResponse;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.entity.StationBookingSetting;
import com.thang.chargeops.station.entity.StationOperatingSchedule;
import com.thang.chargeops.station.entity.TouRate;
import com.thang.chargeops.station.exception.StationPricingDomainException;
import com.thang.chargeops.station.mapper.StationPricingMapper;
import com.thang.chargeops.station.policy.StationPricingPolicy;
import com.thang.chargeops.station.repository.StationBookingSettingsRepository;
import com.thang.chargeops.station.repository.StationOperatingScheduleRepository;
import com.thang.chargeops.station.repository.StationRepository;
import com.thang.chargeops.station.repository.TouRateRepository;
import com.thang.chargeops.station.service.StationPricingService;
import com.thang.chargeops.station.service.model.StationPriceRange;
import com.thang.chargeops.station.service.support.StationPricingExceptionTranslator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class StationPricingServiceImpl implements StationPricingService {

    private final StationRepository stationRepository;
    private final StationBookingSettingsRepository stationBookingSettingsRepository;
    private final StationOperatingScheduleRepository stationOperatingScheduleRepository;
    private final TouRateRepository touRateRepository;
    private final CurrentProfileProvider currentProfileProvider;
    private final StationPricingPolicy stationPricingPolicy;
    private final StationPricingMapper stationPricingMapper;
    private final StationPricingExceptionTranslator stationPricingExceptionTranslator;
    private final Clock applicationClock;

    @Override
    @Transactional(readOnly = true)
    public StationPricingResponse getStationPricing(UUID stationId) {
        Station station = requireStation(stationId);
        stationPricingPolicy.validateStationOwnershipAndStatus(
                station,
                currentProfileProvider.requireProfileId()
        );
        return buildResponse(station, applicationClock.instant());
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

        try {
            return applyPricingUpdate(stationId, station, request);
        } catch (StationPricingDomainException exception) {
            throw stationPricingExceptionTranslator.translate(exception);
        }
    }

    private StationPricingResponse applyPricingUpdate(
            UUID stationId,
            Station station,
            UpdateStationPricingRequest request
    ) {
        stationPricingPolicy.validateBookingSettings(request.minBookingDurationMin());
        stationPricingPolicy.validateOperatingHours(request.open24Hours(), request.hours());
        stationPricingPolicy.validateTouRules(request.touRules());

        Instant effectiveAt = applicationClock.instant();
        StationBookingSetting settings = stationBookingSettingsRepository.findByStationId(stationId)
                .orElseGet(() -> StationBookingSetting.createDefault(station));
        settings.updatePricing(request.minBookingDurationMin(), request.basePriceVnd());
        stationBookingSettingsRepository.save(settings);

        stationOperatingScheduleRepository.findActiveByStationId(stationId, effectiveAt)
                .ifPresent(active -> active.expireAt(effectiveAt));
        stationOperatingScheduleRepository.flush();

        StationOperatingSchedule schedule = StationOperatingSchedule.create(
                station,
                request.open24Hours(),
                effectiveAt
        );
        if (!request.open24Hours()) {
            request.hours().forEach(hour -> schedule.addPeriod(
                    hour.day(),
                    hour.enabled() ? hour.openTime() : null,
                    hour.enabled() ? hour.closeTime() : null,
                    hour.enabled()
            ));
        }
        stationOperatingScheduleRepository.save(schedule);

        List<TouRate> previousRates = touRateRepository.findActiveByStationId(stationId, effectiveAt);
        previousRates.forEach(rate -> rate.expireAt(effectiveAt));
        touRateRepository.saveAll(previousRates);

        List<TouRate> newRates = request.touRules().stream()
                .map(rule -> TouRate.create(
                        station,
                        rule.name(),
                        rule.periodCode(),
                        rule.dayType(),
                        rule.startTime(),
                        rule.endTime(),
                        rule.rateVnd(),
                        effectiveAt
                ))
                .toList();
        touRateRepository.saveAll(newRates);

        log.info("Updated pricing configuration for station {}", stationId);
        return stationPricingMapper.toResponse(stationId, settings, schedule, newRates);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal resolvePriceAt(UUID stationId, Instant targetTime) {
        requireStation(stationId);
        BigDecimal basePrice = stationBookingSettingsRepository.findByStationId(stationId)
                .map(StationBookingSetting::getBasePriceVnd)
                .orElse(StationBookingSetting.DEFAULT_BASE_PRICE_VND);

        ZonedDateTime local = targetTime.atZone(
                ZoneId.of(SystemConstant.SYSTEM_REGION_TIMEZONE)
        );
        return touRateRepository.findActiveByStationId(stationId, targetTime).stream()
                .filter(rate -> rate.appliesAt(local.getDayOfWeek(), local.toLocalTime()))
                .map(TouRate::getPricePerKwh)
                .findFirst()
                .orElse(basePrice);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StationPriceRange> resolvePriceRanges(
            UUID stationId,
            Instant effectiveAt,
            Instant rangeStart,
            Instant rangeEnd
    ) {
        if (effectiveAt == null || rangeStart == null || rangeEnd == null
                || !rangeStart.isBefore(rangeEnd)) {
            throw new IllegalArgumentException("Pricing range requires a valid effective time and time window");
        }

        requireStation(stationId);
        BigDecimal basePrice = stationBookingSettingsRepository.findByStationId(stationId)
                .map(StationBookingSetting::getBasePriceVnd)
                .orElse(StationBookingSetting.DEFAULT_BASE_PRICE_VND);
        List<TouRate> rates = touRateRepository.findActiveByStationId(
                stationId,
                effectiveAt
        );

        ZoneId systemZoneId = ZoneId.of(SystemConstant.SYSTEM_REGION_TIMEZONE);
        NavigableSet<Instant> boundaries = priceBoundaries(
                rates,
                rangeStart,
                rangeEnd,
                systemZoneId
        );
        List<Instant> orderedBoundaries = List.copyOf(boundaries);
        List<StationPriceRange> ranges = new ArrayList<>();

        for (int index = 0; index < orderedBoundaries.size() - 1; index++) {
            Instant startAt = orderedBoundaries.get(index);
            Instant endAt = orderedBoundaries.get(index + 1);
            ResolvedPrice price = resolvePrice(rates, basePrice, startAt, systemZoneId);
            appendOrMergePriceRange(ranges, startAt, endAt, price);
        }
        return List.copyOf(ranges);
    }

    private NavigableSet<Instant> priceBoundaries(
            List<TouRate> rates,
            Instant rangeStart,
            Instant rangeEnd,
            ZoneId zoneId
    ) {
        NavigableSet<Instant> boundaries = new TreeSet<>();
        boundaries.add(rangeStart);
        boundaries.add(rangeEnd);

        LocalDate firstCandidateDate = rangeStart.atZone(zoneId)
                .toLocalDate()
                .minusDays(1);
        LocalDate lastCandidateDate = rangeEnd.atZone(zoneId)
                .toLocalDate()
                .plusDays(1);

        for (LocalDate date = firstCandidateDate;
                !date.isAfter(lastCandidateDate);
                date = date.plusDays(1)) {
            for (TouRate rate : rates) {
                addBoundaryWithinRange(
                        boundaries,
                        date.atTime(rate.getStartTime()).atZone(zoneId).toInstant(),
                        rangeStart,
                        rangeEnd
                );
                addBoundaryWithinRange(
                        boundaries,
                        date.atTime(rate.getEndTime()).atZone(zoneId).toInstant(),
                        rangeStart,
                        rangeEnd
                );
            }
        }
        return boundaries;
    }

    private void addBoundaryWithinRange(
            NavigableSet<Instant> boundaries,
            Instant candidate,
            Instant rangeStart,
            Instant rangeEnd
    ) {
        if (candidate.isAfter(rangeStart) && candidate.isBefore(rangeEnd)) {
            boundaries.add(candidate);
        }
    }

    private ResolvedPrice resolvePrice(
            List<TouRate> rates,
            BigDecimal basePrice,
            Instant at,
            ZoneId zoneId
    ) {
        ZonedDateTime localAt = at.atZone(zoneId);
        return rates.stream()
                .filter(rate -> rate.appliesAt(
                        localAt.getDayOfWeek(),
                        localAt.toLocalTime()
                ))
                .findFirst()
                .map(rate -> new ResolvedPrice(
                        rate.getPricePerKwh(),
                        rate.getPeriodCode()
                ))
                .orElseGet(() -> new ResolvedPrice(
                        basePrice,
                        TouRatePeriodCode.NORMAL
                ));
    }

    private void appendOrMergePriceRange(
            List<StationPriceRange> ranges,
            Instant startAt,
            Instant endAt,
            ResolvedPrice price
    ) {
        if (!ranges.isEmpty()) {
            StationPriceRange previous = ranges.getLast();
            boolean sameRate = previous.rateVndPerKwh()
                    .compareTo(price.rateVndPerKwh()) == 0;
            if (previous.endAt().equals(startAt)
                    && sameRate
                    && previous.periodCode() == price.periodCode()) {
                ranges.set(
                        ranges.size() - 1,
                        new StationPriceRange(
                                previous.startAt(),
                                endAt,
                                price.rateVndPerKwh(),
                                price.periodCode()
                        )
                );
                return;
            }
        }

        ranges.add(new StationPriceRange(
                startAt,
                endAt,
                price.rateVndPerKwh(),
                price.periodCode()
        ));
    }

    private record ResolvedPrice(
            BigDecimal rateVndPerKwh,
            TouRatePeriodCode periodCode
    ) {
    }

    private StationPricingResponse buildResponse(Station station, Instant at) {
        StationBookingSetting settings = stationBookingSettingsRepository.findByStationId(station.getId())
                .orElseGet(() -> StationBookingSetting.createDefault(station));
        StationOperatingSchedule schedule = stationOperatingScheduleRepository
                .findActiveByStationId(station.getId(), at)
                .orElse(null);
        List<TouRate> rates = touRateRepository.findActiveByStationId(station.getId(), at);
        return stationPricingMapper.toResponse(station.getId(), settings, schedule, rates);
    }

    private Station requireStation(UUID stationId) {
        return stationRepository.findById(stationId)
                .orElseThrow(() -> new AppException(
                        StationErrorCode.STATION_NOT_FOUND,
                        stationId
                ));
    }
}
