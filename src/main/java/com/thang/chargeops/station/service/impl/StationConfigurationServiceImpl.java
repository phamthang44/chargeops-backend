package com.thang.chargeops.station.service.impl;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.exception.AppException;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
                .findByStationIdOrderByEffectiveFromDesc(stationId);

        return schedules.stream()
                .map(schedule -> {
                    UUID actorId = schedule.getCreatedBy() != null
                            ? schedule.getCreatedBy()
                            : schedule.getUpdatedBy();
                    String status = schedule.isActive(now) ? "ACTIVE" : "EXPIRED";
                    return new StationScheduleHistoryResponse(
                            schedule.getId(),
                            schedule.getEffectiveFrom(),
                            schedule.getEffectiveTo(),
                            status,
                            schedule.isOpen24Hours(),
                            stationPricingMapper.operatingHours(schedule),
                            resolveRecordedByName(actorId),
                            schedule.getCreatedAt() != null
                                    ? schedule.getCreatedAt()
                                    : schedule.getEffectiveFrom()
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

        Instant effectiveAt = applicationClock.instant();
        StationBookingSetting settings = stationBookingSettingsRepository
                .findByStationId(stationId)
                .orElseGet(() -> StationBookingSetting.createDefault(station));
        settings.updatePricing(
                request.minBookingDurationMin(),
                request.basePriceVnd()
        );
        stationBookingSettingsRepository.save(settings);

        stationOperatingScheduleRepository
                .findActiveByStationId(stationId, effectiveAt)
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

        List<TouRate> previousRates = touRateRepository
                .findActiveByStationId(stationId, effectiveAt);
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

        log.info("Updated commercial configuration for station {}", stationId);
        return stationPricingMapper.toResponse(
                stationId,
                settings,
                schedule,
                newRates
        );
    }

    private StationPricingResponse buildResponse(Station station, Instant at) {
        StationBookingSetting settings = stationBookingSettingsRepository
                .findByStationId(station.getId())
                .orElseGet(() -> StationBookingSetting.createDefault(station));
        StationOperatingSchedule schedule = stationOperatingScheduleRepository
                .findActiveByStationId(station.getId(), at)
                .orElse(null);
        List<TouRate> rates = touRateRepository.findActiveByStationId(
                station.getId(),
                at
        );
        return stationPricingMapper.toResponse(
                station.getId(),
                settings,
                schedule,
                rates
        );
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
