package com.thang.chargeops.station.service.usecase;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.station.dto.license.response.LicenseSummaryResponse;
import com.thang.chargeops.station.dto.station.filter.StationFilter;
import com.thang.chargeops.station.dto.station.response.AdminStationDetailResponse;
import com.thang.chargeops.station.dto.station.response.AdminStationListItemResponse;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.entity.StationAsset;
import com.thang.chargeops.station.entity.StationOperatingPeriod;
import com.thang.chargeops.station.mapper.StationMapper;
import com.thang.chargeops.station.repository.LicenseRepository;
import com.thang.chargeops.station.repository.StationAssetRepository;
import com.thang.chargeops.station.repository.StationOperatingPeriodRepository;
import com.thang.chargeops.station.repository.StationOperatingScheduleRepository;
import com.thang.chargeops.station.repository.StationRepository;
import com.thang.chargeops.station.repository.specs.StationSpecification;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminStationQueryUseCase {

    private final StationRepository stationRepository;
    private final StationMapper stationMapper;
    private final LicenseRepository licenseRepository;
    private final StationAssetRepository stationAssetRepository;
    private final StationOperatingScheduleRepository scheduleRepository;
    private final StationOperatingPeriodRepository periodRepository;

    @Transactional(readOnly = true)
    public Station getById(UUID stationId) {
        return stationRepository.findApprovalDetailById(stationId)
                .orElseThrow(() -> new AppException(
                        StationErrorCode.STATION_NOT_FOUND,
                        stationId
                ));
    }

    @Transactional(readOnly = true)
    public Page<AdminStationListItemResponse> getStations(
            Pageable pageable,
            StationFilter filter
    ) {
        Page<Station> stations = stationRepository.findAll(
                StationSpecification.filter(filter),
                pageable
        );
        List<UUID> stationIds = stations.stream()
                .map(Station::getId)
                .toList();
        Map<UUID, LicenseSummaryResponse> licensesByStationId = stationIds.isEmpty()
                ? Map.of()
                : licenseRepository.findActiveByStationIds(
                                stationIds,
                                Instant.now()
                        ).stream()
                        .collect(Collectors.toMap(
                                license -> license.getStation().getId(),
                                license -> new LicenseSummaryResponse(
                                        license.getPlan(),
                                        license.getExpiresAt()
                                )
                        ));

        return stations.map(station -> stationMapper
                .toAdminStationListItemResponse(
                        station,
                        licensesByStationId.get(station.getId())
                ));
    }

    @Transactional(readOnly = true)
    public AdminStationDetailResponse getDetail(UUID stationId) {
        Station station = stationRepository.findAdminDetailById(stationId)
                .orElseThrow(() -> new AppException(
                        StationErrorCode.STATION_NOT_FOUND,
                        stationId
                ));

        LicenseSummaryResponse licenseSummary = licenseRepository
                .findActiveByStationId(stationId, Instant.now())
                .map(license -> new LicenseSummaryResponse(
                        license.getPlan(),
                        license.getExpiresAt()
                ))
                .orElse(null);

        List<StationAsset> assets = stationAssetRepository
                .findByStationIdOrderByDisplayOrderAsc(stationId);
        station.setAssets(assets);

        List<StationOperatingPeriod> operatingPeriods = scheduleRepository
                .findActiveByStationId(stationId, Instant.now())
                .map(schedule -> periodRepository
                        .findByScheduleIdOrderByDayOfWeekAscOpenTimeAsc(
                                schedule.getId()
                        ))
                .orElse(List.of());

        return stationMapper.toAdminStationDetailResponse(
                station,
                operatingPeriods,
                licenseSummary
        );
    }
}
