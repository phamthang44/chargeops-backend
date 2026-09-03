package com.thang.chargeops.station.service.impl;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.enums.TouRatePeriodCode;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.entity.StationBookingSetting;
import com.thang.chargeops.station.entity.TouRate;
import com.thang.chargeops.station.repository.StationBookingSettingsRepository;
import com.thang.chargeops.station.repository.StationRepository;
import com.thang.chargeops.station.repository.TouRateRepository;
import com.thang.chargeops.station.service.StationPricingService;
import com.thang.chargeops.station.service.model.StationPriceRange;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
@RequiredArgsConstructor
public class StationPricingServiceImpl implements StationPricingService {

    private final StationRepository stationRepository;
    private final StationBookingSettingsRepository stationBookingSettingsRepository;
    private final TouRateRepository touRateRepository;

    @Override
    @Transactional(readOnly = true)
    public BigDecimal resolvePriceAt(UUID stationId, Instant targetTime) {
        requireStation(stationId);
        BigDecimal basePrice = stationBookingSettingsRepository
                .findByStationId(stationId)
                .map(StationBookingSetting::getBasePriceVnd)
                .orElse(StationBookingSetting.DEFAULT_BASE_PRICE_VND);

        ZonedDateTime local = targetTime.atZone(
                ZoneId.of(SystemConstant.SYSTEM_REGION_TIMEZONE)
        );
        return touRateRepository.findActiveByStationId(stationId, targetTime)
                .stream()
                .filter(rate -> rate.appliesAt(
                        local.getDayOfWeek(),
                        local.toLocalTime()
                ))
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
            throw new IllegalArgumentException(
                    "Pricing range requires a valid effective time and time window"
            );
        }

        requireStation(stationId);
        BigDecimal basePrice = stationBookingSettingsRepository
                .findByStationId(stationId)
                .map(StationBookingSetting::getBasePriceVnd)
                .orElse(StationBookingSetting.DEFAULT_BASE_PRICE_VND);
        List<TouRate> rates = touRateRepository.findActiveByStationId(
                stationId,
                effectiveAt
        );

        ZoneId systemZoneId = ZoneId.of(
                SystemConstant.SYSTEM_REGION_TIMEZONE
        );
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
            ResolvedPrice price = resolvePrice(
                    rates,
                    basePrice,
                    startAt,
                    systemZoneId
            );
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
                        date.atTime(rate.getStartTime())
                                .atZone(zoneId)
                                .toInstant(),
                        rangeStart,
                        rangeEnd
                );
                addBoundaryWithinRange(
                        boundaries,
                        date.atTime(rate.getEndTime())
                                .atZone(zoneId)
                                .toInstant(),
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

    private Station requireStation(UUID stationId) {
        return stationRepository.findById(stationId)
                .orElseThrow(() -> new AppException(
                        StationErrorCode.STATION_NOT_FOUND,
                        stationId
                ));
    }

    private record ResolvedPrice(
            BigDecimal rateVndPerKwh,
            TouRatePeriodCode periodCode
    ) {
    }
}
