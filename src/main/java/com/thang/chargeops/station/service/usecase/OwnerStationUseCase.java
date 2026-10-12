package com.thang.chargeops.station.service.usecase;

import com.thang.chargeops.common.enums.StationOperationalStatus;
import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.booking.repository.OwnerBookingReadRepository;
import com.thang.chargeops.station.repository.ChargePointRepository;
import com.thang.chargeops.station.repository.ConnectorRepository;
import com.thang.chargeops.station.dto.station.response.OwnerOperationsSummaryResponse;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.dto.station.request.ChangeStationOperationalStatusRequest;
import com.thang.chargeops.station.dto.station.response.OwnerStationSummaryResponse;
import com.thang.chargeops.station.dto.station.response.StationOperationalStatusResponse;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.entity.StationOperatingSchedule;
import com.thang.chargeops.station.entity.StationOperationalStatusEvent;
import com.thang.chargeops.station.mapper.StationMapper;
import com.thang.chargeops.station.policy.StationOperationalStatusPolicy;
import com.thang.chargeops.station.projection.OwnerStationSummaryProjection;
import com.thang.chargeops.station.repository.StationOperatingScheduleRepository;
import com.thang.chargeops.station.repository.StationOperationalStatusEventRepository;
import com.thang.chargeops.station.repository.StationRepository;
import com.thang.chargeops.station.service.support.StationEffectiveStateResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OwnerStationUseCase {

    private final StationRepository stationRepository;
    private final StationMapper stationMapper;
    private final CurrentProfileProvider currentProfileProvider;
    private final StationOperatingScheduleRepository scheduleRepository;
    private final StationEffectiveStateResolver effectiveStateResolver;
    private final StationOperationalStatusPolicy operationalStatusPolicy;
    private final StationOperationalStatusEventRepository statusEventRepository;
    private final ChargePointRepository chargePointRepository;
    private final ConnectorRepository connectorRepository;
    private final OwnerBookingReadRepository ownerBookingReadRepository;
    private final Clock applicationClock;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OwnerOperationsSummaryResponse getOperationsSummary() {
        UUID ownerId = currentProfileProvider.requireProfile().getId();
        Instant now = applicationClock.instant();
        ZoneId zone = ZoneId.of(SystemConstant.SYSTEM_REGION_TIMEZONE);
        var date = now.atZone(zone).toLocalDate();
        Instant dayStart = date.atStartOfDay(zone).toInstant();
        var stations = stationRepository.summarizeOwnerOperations(ownerId, now);
        var points = chargePointRepository.summarizeOwnerOperations(ownerId);
        var connectors = connectorRepository.summarizeOwnerOperations(ownerId);
        var windows = ownerBookingReadRepository.findOwnerChargingWindows(ownerId, dayStart, now);
        long sessionsToday = 0;
        BigDecimal chargingMillis = BigDecimal.ZERO;
        for (var window : windows) {
            Instant startedAt = window.getChargingStartedAt();
            if (!startedAt.isBefore(dayStart)) sessionsToday++;
            Instant finishedAt = window.getCompletedAt() != null ? window.getCompletedAt()
                    : window.getCancelledAt() != null ? window.getCancelledAt() : window.getEndAt();
            Instant from = startedAt.isAfter(dayStart) ? startedAt : dayStart;
            Instant to = finishedAt.isBefore(window.getEndAt()) ? finishedAt : window.getEndAt();
            if (to.isAfter(now)) to = now;
            if (to.isAfter(from)) {
                chargingMillis = chargingMillis.add(BigDecimal.valueOf(Duration.between(from, to).toMillis()));
            }
        }
        BigDecimal capacityMillis = BigDecimal.valueOf(Duration.between(dayStart, now).toMillis())
                .multiply(BigDecimal.valueOf(connectors.getTotalConnectors()));
        BigDecimal utilization = capacityMillis.signum() == 0 ? BigDecimal.ZERO.setScale(2)
                : chargingMillis.multiply(BigDecimal.valueOf(100))
                    .divide(capacityMillis, 2, RoundingMode.HALF_UP).min(BigDecimal.valueOf(100));
        return new OwnerOperationsSummaryResponse(now, date, zone.getId(),
                new OwnerOperationsSummaryResponse.Stations(stations.getTotalStations(), stations.getActiveStations(),
                        stations.getVisibleToDrivers(), stations.getPendingApproval(),
                        points.getOnlineChargePoints(), points.getTotalChargePoints()),
                new OwnerOperationsSummaryResponse.Hardware(connectors.getTotalConnectors(),
                        connectors.getAvailableConnectors(), connectors.getChargingConnectors(),
                        connectors.getOfflineConnectors(), connectors.getUnavailableConnectors(), sessionsToday, utilization));
    }

    @Transactional(readOnly = true)
    public Page<OwnerStationSummaryResponse> getMyStations(Pageable pageable) {
        UserProfile owner = currentProfileProvider.requireProfile();
        Instant now = Instant.now();
        Page<OwnerStationSummaryProjection> stations =
                stationRepository.findOwnerStationSummaries(
                        owner.getId(),
                        now,
                        LocaleContextHolder.getLocale().getLanguage(),
                        pageable
                );

        List<UUID> stationIds = stations.stream()
                .map(OwnerStationSummaryProjection::getId)
                .toList();
        Map<UUID, StationOperatingSchedule> schedulesByStationId =
                findSchedulesByStationId(stationIds, now);

        return stations.map(station -> stationMapper
                .toOwnerStationSummaryResponse(
                        station,
                        effectiveStateResolver.resolve(
                                isVisibleToDrivers(station),
                                station.getOperationalStatus(),
                                schedulesByStationId.get(station.getId()),
                                now
                        )
                ));
    }

    @Transactional
    public StationOperationalStatusResponse changeOperationalStatus(
            UUID stationId,
            ChangeStationOperationalStatusRequest request
    ) {
        UserProfile owner = currentProfileProvider.requireProfile();
        Station station = stationRepository
                .findByIdForOperationalStatusUpdate(stationId)
                .orElseThrow(() -> new AppException(
                        StationErrorCode.STATION_NOT_FOUND,
                        stationId
                ));
        if (!station.getOwner().getId().equals(owner.getId())) {
            throw new AppException(
                    StationErrorCode.STATION_ACCESS_DENIED,
                    stationId
            );
        }

        Instant now = Instant.now();
        operationalStatusPolicy.requireCanChange(
                station,
                request.operationalStatus(),
                request.reason(),
                now
        );

        StationOperationalStatus previousStatus = station.getOperationalStatus();
        if (previousStatus != request.operationalStatus()) {
            station.updateOperationalStatus(
                    request.operationalStatus(),
                    request.reason()
            );
            statusEventRepository.save(
                    StationOperationalStatusEvent.transition(
                            station,
                            previousStatus,
                            station.getOperationalStatus(),
                            owner,
                            now,
                            station.getOperationalStatusReason()
                    )
            );
        }

        return new StationOperationalStatusResponse(
                station.getId(),
                station.getOperationalStatus(),
                station.getOperationalStatusReason()
        );
    }

    private Map<UUID, StationOperatingSchedule> findSchedulesByStationId(
            List<UUID> stationIds,
            Instant at
    ) {
        if (stationIds.isEmpty()) {
            return Map.of();
        }
        return scheduleRepository.findActiveByStationIds(stationIds, at)
                .stream()
                .collect(Collectors.toMap(
                        schedule -> schedule.getStation().getId(),
                        schedule -> schedule
                ));
    }

    private boolean isVisibleToDrivers(OwnerStationSummaryProjection station) {
        return station.getStatus() == StationStatus.ACTIVE
                && station.getLicensePlan() != null
                && station.getLicenseExpiresAt() != null;
    }
}
