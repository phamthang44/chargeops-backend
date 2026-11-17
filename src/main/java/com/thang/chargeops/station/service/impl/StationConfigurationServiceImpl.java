package com.thang.chargeops.station.service.impl;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.service.UserProfileService;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.dto.station.request.UpdateStationPricingRequest;
import com.thang.chargeops.station.dto.station.response.StationPricingResponse;
import com.thang.chargeops.station.dto.station.response.StationScheduleHistoryResponse;
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
import com.thang.chargeops.station.service.StationConfigurationService;
import com.thang.chargeops.station.service.support.StationPricingExceptionTranslator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class StationConfigurationServiceImpl implements StationConfigurationService {

    private final StationRepository stationRepository;
    private final StationBookingSettingsRepository stationBookingSettingsRepository;
    private final StationOperatingScheduleRepository stationOperatingScheduleRepository;
    private final TouRateRepository touRateRepository;
    private final CurrentProfileProvider currentProfileProvider;
    private final StationPricingPolicy stationPricingPolicy;
    private final StationPricingMapper stationPricingMapper;
    private final StationPricingExceptionTranslator stationPricingExceptionTranslator;
    private final Clock applicationClock;
    private final UserProfileService userProfileService;
    private final EntityManager entityManager;

    @Override
    @Transactional(readOnly = true)
    public StationPricingResponse getConfiguration(UUID stationId) {
        Station station = requireStation(stationId);
        stationPricingPolicy.validateStationOwnershipAndStatus(
                station,
                currentProfileProvider.requireProfileId()
        );
        return buildResponse(station, applicationClock.instant());
    }

    @Override
    @Transactional
    public StationPricingResponse updateConfiguration(
            UUID stationId,
            UpdateStationPricingRequest request
    ) {
        Station station = stationRepository.findByIdForPricingUpdate(stationId)
                .orElseThrow(() -> new AppException(
                        StationErrorCode.STATION_NOT_FOUND,
                        stationId
                ));
        stationPricingPolicy.validateStationOwnershipAndStatus(
                station,
                currentProfileProvider.requireProfileId()
        );

        try {
            return applyConfigurationUpdate(stationId, station, request);
        } catch (StationPricingDomainException exception) {
            throw stationPricingExceptionTranslator.translate(exception);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<StationScheduleHistoryResponse> getScheduleHistory(UUID stationId) {
        Station station = requireStation(stationId);
        stationPricingPolicy.validateStationOwnershipAndStatus(
                station,
                currentProfileProvider.requireProfileId()
        );

        Instant now = applicationClock.instant();
        List<StationOperatingSchedule> schedules = stationOperatingScheduleRepository
                .findByStationIdOrderByEffectiveFromDescCreatedAtDesc(stationId);

        List<UUID> scheduleIds = schedules.stream()
                .map(StationOperatingSchedule::getId)
                .filter(id -> id != null)
                .toList();
        Map<UUID, List<TouRate>> ratesBySchedule = scheduleIds.isEmpty()
                ? Map.of()
                : touRateRepository.findByScheduleIdInOrderByDayTypeAscStartTimeAsc(scheduleIds)
                        .stream()
                        .collect(Collectors.groupingBy(TouRate::getScheduleId));

        return schedules.stream()
                .map(schedule -> {
                    UUID actorId = schedule.getCreatedBy() != null
                            ? schedule.getCreatedBy()
                            : schedule.getUpdatedBy();
                    List<TouRate> rates = ratesBySchedule.getOrDefault(
                            schedule.getId(),
                            List.of()
                    );
                    return new StationScheduleHistoryResponse(
                            schedule.getId(),
                            schedule.getEffectiveFrom(),
                            schedule.getEffectiveTo(),
                            schedule.statusAt(now),
                            schedule.isOpen24Hours(),
                            stationPricingMapper.operatingHours(schedule),
                            schedule.getMinDurationMinutes(),
                            schedule.getBasePriceVnd(),
                            stationPricingMapper.mapTouRules(rates),
                            resolveRecordedByName(actorId),
                            schedule.getCreatedAt() != null
                                    ? schedule.getCreatedAt()
                                    : schedule.getEffectiveFrom(),
                            schedule.getCancelledAt(),
                            schedule.getCancelledAt() == null
                                    ? null
                                    : resolveRecordedByName(schedule.getCancelledByProfileId())
                    );
                })
                .toList();
    }

    private StationPricingResponse applyConfigurationUpdate(
            UUID stationId,
            Station station,
            UpdateStationPricingRequest request
    ) {
        stationPricingPolicy.validateBookingSettings(request.minBookingDurationMin());
        stationPricingPolicy.validateOperatingHours(
                request.open24Hours(),
                request.hours()
        );
        stationPricingPolicy.validateTouRules(request.touRules());

        Instant now = applicationClock.instant();
        StationBookingSetting settings = stationBookingSettingsRepository
                .findByStationId(stationId)
                .orElse(null);

        long currentVersion = settings != null ? settings.getVersion() + 1L : 0L;
        long expectedVersion = request.version() != null ? request.version() : 0L;

        if (expectedVersion != currentVersion) {
            throw new AppException(StationErrorCode.PRICING_CONFIGURATION_CONFLICT);
        }

        StationOperatingSchedule activeSchedule = stationOperatingScheduleRepository
                .findActiveByStationId(stationId, now)
                .orElse(null);
        StationOperatingSchedule pendingSchedule = stationOperatingScheduleRepository
                .findPendingByStationId(stationId, now)
                .stream()
                .findFirst()
                .orElse(null);
        UUID actorProfileId = currentProfileProvider.requireProfileId();

        List<TouRate> activeRates = activeSchedule == null
                ? List.of()
                : ratesForSchedule(activeSchedule, stationId, now);
        List<TouRate> pendingRates = pendingSchedule == null
                ? List.of()
                : touRateRepository.findByScheduleIdOrderByDayTypeAscStartTimeAsc(
                        pendingSchedule.getId()
                );

        if (matchesRequest(activeSchedule, activeRates, request)) {
            if (pendingSchedule == null) {
                return buildResponse(station, now);
            }

            pendingSchedule.cancelAt(now, actorProfileId);
            activeSchedule.resumeIndefinitelyAt(pendingSchedule.getEffectiveFrom());
            settings.updatePricing(
                    activeSchedule.getMinDurationMinutes(),
                    activeSchedule.getBasePriceVnd()
            );
            stationOperatingScheduleRepository.save(activeSchedule);
            stationOperatingScheduleRepository.save(pendingSchedule);
            lockConfigurationVersion(settings);
            stationBookingSettingsRepository.save(settings);
            return buildResponse(station, now);
        }

        if (matchesRequest(pendingSchedule, pendingRates, request)) {
            return buildResponse(station, now);
        }

        Instant effectiveAt = hasCommercialValues(activeSchedule)
                ? nextWeeklyBoundary(now)
                : now;
        StationOperatingSchedule schedule = createSchedule(station, request, effectiveAt);

        stationPricingPolicy.validateOperatingHoursNotConflictingWithActiveBookings(
                stationId,
                schedule,
                now
        );

        if (settings == null) {
            settings = StationBookingSetting.createDefault(station);
            settings.updatePricing(
                    request.minBookingDurationMin(),
                    request.basePriceVnd()
            );
        } else {
            lockConfigurationVersion(settings);
        }

        if (activeSchedule != null) {
            activeSchedule.expireAt(effectiveAt);
            stationOperatingScheduleRepository.save(activeSchedule);
        }
        if (pendingSchedule != null) {
            pendingSchedule.cancelAt(now, actorProfileId);
            stationOperatingScheduleRepository.save(pendingSchedule);
        }
        stationOperatingScheduleRepository.flush();

        StationOperatingSchedule savedSchedule = stationOperatingScheduleRepository
                .saveAndFlush(schedule);

        List<TouRate> previousRates = touRateRepository
                .findActiveByStationId(stationId, now);
        previousRates.forEach(rate -> rate.expireAt(effectiveAt));
        touRateRepository.saveAll(previousRates);

        List<TouRate> newRates = request.touRules().stream()
                .map(rule -> TouRate.create(
                        station,
                        savedSchedule.getId(),
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
        stationBookingSettingsRepository.save(settings);

        log.info("Updated commercial configuration for station {}", stationId);
        return buildResponse(station, now);
    }

    private StationPricingResponse buildResponse(Station station, Instant at) {
        StationBookingSetting settings = stationBookingSettingsRepository
                .findByStationId(station.getId())
                .orElse(null);
        StationOperatingSchedule schedule = stationOperatingScheduleRepository
                .findActiveByStationId(station.getId(), at)
                .orElse(null);
        List<TouRate> rates = schedule == null
                ? List.of()
                : ratesForSchedule(schedule, station.getId(), at);
        StationPricingResponse current = stationPricingMapper.toResponse(
                station.getId(),
                settings,
                schedule,
                rates
        );
        StationOperatingSchedule pending = stationOperatingScheduleRepository
                .findPendingByStationId(station.getId(), at)
                .stream()
                .findFirst()
                .orElse(null);
        List<TouRate> pendingRates = pending == null
                ? List.of()
                : touRateRepository.findByScheduleIdOrderByDayTypeAscStartTimeAsc(
                        pending.getId()
                );
        return stationPricingMapper.toOwnerResponse(current, pending, pendingRates);
    }

    private List<TouRate> ratesForSchedule(
            StationOperatingSchedule schedule,
            UUID stationId,
            Instant at
    ) {
        List<TouRate> versionRates = touRateRepository
                .findByScheduleIdOrderByDayTypeAscStartTimeAsc(schedule.getId());
        if (hasCommercialValues(schedule)) {
            return versionRates;
        }
        return touRateRepository.findActiveByStationId(stationId, at);
    }

    private boolean hasCommercialValues(StationOperatingSchedule schedule) {
        return schedule != null
                && schedule.getBasePriceVnd() != null
                && schedule.getMinDurationMinutes() != null;
    }

    private StationOperatingSchedule createSchedule(
            Station station,
            UpdateStationPricingRequest request,
            Instant effectiveAt
    ) {
        StationOperatingSchedule schedule = StationOperatingSchedule.create(
                station,
                request.open24Hours(),
                effectiveAt,
                request.minBookingDurationMin(),
                request.basePriceVnd()
        );
        if (!request.open24Hours()) {
            request.hours().forEach(hour -> schedule.addPeriod(
                    hour.day(),
                    hour.enabled() ? hour.openTime() : null,
                    hour.enabled() ? hour.closeTime() : null,
                    hour.enabled()
            ));
        }
        return schedule;
    }

    private void lockConfigurationVersion(StationBookingSetting settings) {
        entityManager.lock(settings, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
    }

    private Instant nextWeeklyBoundary(Instant now) {
        ZoneId zone = ZoneId.of(SystemConstant.SYSTEM_REGION_TIMEZONE);
        LocalDate nextMonday = now.atZone(zone)
                .toLocalDate()
                .with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        return nextMonday.atStartOfDay(zone).toInstant();
    }

    private boolean matchesRequest(
            StationOperatingSchedule schedule,
            List<TouRate> rates,
            UpdateStationPricingRequest request
    ) {
        if (schedule == null || schedule.getMinDurationMinutes() == null
                || schedule.getBasePriceVnd() == null
                || schedule.getMinDurationMinutes() != request.minBookingDurationMin()
                || schedule.getBasePriceVnd().compareTo(request.basePriceVnd()) != 0
                || schedule.isOpen24Hours() != request.open24Hours()
                || !matchesHours(schedule, request)
                || rates.size() != request.touRules().size()) {
            return false;
        }

        return request.touRules().stream().allMatch(rule -> rates.stream().anyMatch(rate ->
                rate.getName().equals(rule.name().trim())
                        && rate.getPeriodCode() == rule.periodCode()
                        && rate.getDayType() == rule.dayType()
                        && rate.getStartTime().equals(rule.startTime())
                        && rate.getEndTime().equals(rule.endTime())
                        && rate.getPricePerKwh().compareTo(rule.rateVnd()) == 0
        ));
    }

    private boolean matchesHours(
            StationOperatingSchedule schedule,
            UpdateStationPricingRequest request
    ) {
        if (request.open24Hours()) {
            return schedule.getPeriods().isEmpty();
        }
        if (schedule.getPeriods().size() != request.hours().size()) {
            return false;
        }
        return request.hours().stream().allMatch(hour -> schedule.getPeriods().stream()
                .filter(period -> period.getDayOfWeek() == hour.day())
                .anyMatch(period -> period.isEnabled() == hour.enabled()
                        && java.util.Objects.equals(
                                period.getOpenTime(),
                                hour.enabled() ? hour.openTime() : null
                        )
                        && java.util.Objects.equals(
                                period.getCloseTime(),
                                hour.enabled() ? hour.closeTime() : null
                        )));
    }

    private Station requireStation(UUID stationId) {
        return stationRepository.findById(stationId)
                .orElseThrow(() -> new AppException(
                        StationErrorCode.STATION_NOT_FOUND,
                        stationId
                ));
    }

    private String resolveRecordedByName(UUID profileId) {
        if (profileId == null) {
            return SystemConstant.SYSTEM_ACTOR;
        }
        try {
            UserProfile profile = userProfileService.getUserProfileById(profileId);
            if (profile != null) {
                if (profile.getDisplayName() != null
                        && !profile.getDisplayName().isBlank()) {
                    return profile.getDisplayName();
                }
                if (profile.getEmail() != null && !profile.getEmail().isBlank()) {
                    return profile.getEmail();
                }
            }
        } catch (Exception exception) {
            log.warn(
                    "Failed to resolve user profile name for id {}",
                    profileId,
                    exception
            );
        }
        return SystemConstant.SYSTEM_ACTOR;
    }
}
