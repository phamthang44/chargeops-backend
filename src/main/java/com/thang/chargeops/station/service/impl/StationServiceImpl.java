package com.thang.chargeops.station.service.impl;

import com.thang.chargeops.common.constant.LogConstant;
import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.common.enums.StationStatusEventType;
import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.ApprovalErrorCode;
import com.thang.chargeops.exception.errorcode.ProfileErrorCode;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.location.entity.AdministrativeWard;
import com.thang.chargeops.location.service.AdministrativeLocationService;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.dto.license.response.LicenseSummaryResponse;
import com.thang.chargeops.station.dto.station.filter.StationFilter;
import com.thang.chargeops.station.dto.station.request.RegisterStationRequest;
import com.thang.chargeops.station.dto.station.request.RejectStationRequest;
import com.thang.chargeops.station.dto.station.response.*;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.mapper.StationMapper;
import com.thang.chargeops.station.policy.StationApprovalPolicy;
import com.thang.chargeops.station.projection.OwnerStationSummaryProjection;
import com.thang.chargeops.station.projection.StationApprovalSummaryProjection;
import com.thang.chargeops.station.repository.LicenseRepository;
import com.thang.chargeops.station.repository.StationRepository;
import com.thang.chargeops.station.repository.specs.StationSpecification;
import com.thang.chargeops.station.service.StationService;
import com.thang.chargeops.station.service.StationStatusHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.UUID;


@Service
@RequiredArgsConstructor
@Slf4j
public class StationServiceImpl implements StationService {

    private final StationRepository stationRepository;
    private final StationMapper stationMapper;
    private final CurrentProfileProvider currentProfileProvider;
    private final AdministrativeLocationService administrativeLocationService;
    private final StationStatusHistoryService stationStatusHistoryService;
    private final StationApprovalPolicy stationApprovalPolicy;

    private final LicenseRepository licenseRepository; //tạm thòời

    @Override
    @Transactional
    public StationCreatedResponse createStationRegistration(RegisterStationRequest request) {
        UserProfile owner = currentProfileProvider.requireProfile();
        log.info(LogConstant.SERVICE_LOG_FORMAT, LogConstant.ACTION_START, "createStationRegistration", owner.getId(), request);
        if (owner.getStatus() != UserStatus.ACTIVE) {
            throw new AppException(ProfileErrorCode.PROFILE_NOT_ACTIVE);
        }
        AdministrativeWard ward =
                administrativeLocationService.requireWard(request.wardCode());

        administrativeLocationService.requireWardBelongsToProvince(
                ward,
                request.provinceCode()
        );

        Station station = stationMapper.toStationEntity(request);
        station.setStationCode(formatStationCode(stationRepository.nextStationCodeSequence()));
        station.setOwner(owner);
        station.setWard(ward);
        station.setStatus(StationStatus.PENDING_APPROVAL);
        Station savedStation = stationRepository.save(station);

        stationStatusHistoryService.recordTransition(
                savedStation,
                StationStatusEventType.SUBMITTED,
                null,
                owner,
                null
        );
        log.info(LogConstant.SERVICE_LOG_FORMAT, LogConstant.ACTION_SUCCESS, "createStationRegistration", owner.getId(), "stationId=" + savedStation.getId() + ", stationCode=" + savedStation.getStationCode());
        return stationMapper.toStationCreatedResponse(savedStation);
    }

    @Override
    @Transactional(readOnly = true)
    public StationApprovalDetailResponse getStationApprovalDetail(UUID stationId) {
        Station station = stationRepository.findApprovalDetailById(stationId)
                .orElseThrow(() -> new AppException(StationErrorCode.STATION_NOT_FOUND, stationId));
        if (station.getStatus() != StationStatus.PENDING_APPROVAL) {
            throw new AppException(ApprovalErrorCode.STATION_NOT_PENDING_APPROVAL,
                    station.getId(),
                    station.getStatus());
        }
        boolean licenseSubmitted = licenseRepository.existsActiveLicenseForStation(
                stationId,
                Instant.now()
        );

        return stationMapper.toStationApprovalDetailResponse(
                station,
                licenseSubmitted
        );
    }

    @Override
    @Transactional
    public void approveStation(UUID id) {
        UserProfile admin = currentProfileProvider.requireProfile();
        log.info(LogConstant.SERVICE_LOG_FORMAT, LogConstant.ACTION_START, "approveStation", admin.getId(), "stationId=" + id);

        Station station = stationRepository.findById(id)
                .orElseThrow(() -> new AppException(StationErrorCode.STATION_NOT_FOUND, id));
        stationApprovalPolicy.requireCanBeApproved(station);

        StationStatus previousStatus = station.getStatus();
        station.setStatus(StationStatus.ACTIVE);

        stationStatusHistoryService.recordTransition(
                station,
                StationStatusEventType.APPROVED,
                previousStatus,
                admin,
                null
        );
        log.info(LogConstant.SERVICE_LOG_FORMAT, LogConstant.ACTION_SUCCESS, "approveStation", admin.getId(), "stationId=" + id + ", status=" + station.getStatus());
    }

    @Override
    @Transactional
    public void rejectStation(UUID id, RejectStationRequest request) {
        UserProfile admin = currentProfileProvider.requireProfile();
        log.info(LogConstant.SERVICE_LOG_FORMAT, LogConstant.ACTION_START, "rejectStation", admin.getId(), "stationId=" + id + ", reason=" + request.getReason());

        Station station = stationRepository.findById(id)
                .orElseThrow(() -> new AppException(StationErrorCode.STATION_NOT_FOUND, id));
        stationApprovalPolicy.requireCanBeRejected(station, request.getReason());
        StationStatus previousStatus = station.getStatus();
        station.setStatus(StationStatus.REJECTED);

        stationStatusHistoryService.recordTransition(
                station,
                StationStatusEventType.REJECTED,
                previousStatus,
                admin,
                request.getReason()
        );
        log.info(LogConstant.SERVICE_LOG_FORMAT, LogConstant.ACTION_SUCCESS, "rejectStation", admin.getId(), "stationId=" + id + ", status=" + station.getStatus());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OwnerStationSummaryResponse> getMyStations(int pageNo, int pageSize) {
        Pageable pageable = getPageable(pageNo, pageSize);

        UserProfile owner = currentProfileProvider.requireProfile();

        Page<OwnerStationSummaryProjection> stations =
                stationRepository.findOwnerStationSummaries(owner.getId(), Instant.now(), pageable);

        return stations.map(stationMapper::toOwnerStationSummaryResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StationApprovalSummaryResponse> getStationApprovals(int pageNo, int pageSize) {
        Pageable pageable = getPageable(pageNo, pageSize);

        Page<StationApprovalSummaryProjection> stations =
                stationRepository.findStationApprovalSummaries(StationStatus.PENDING_APPROVAL, pageable);

        return stations.map(stationMapper::toStationApprovalSummaryResponse);
    }

    @Override
    public Station getStationById(UUID stationId) {
        return stationRepository.findApprovalDetailById(stationId)
                .orElseThrow(() -> new AppException(StationErrorCode.STATION_NOT_FOUND, stationId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AdminStationListItemResponse> getAdminStations(
            int pageNo,
            int pageSize,
            StationFilter filter
    ) {
        Pageable pageable = getPageable(pageNo, pageSize);
        Page<Station> stations = stationRepository.findAll(
                StationSpecification.filter(filter),
                pageable
        );

        var stationIds = stations.stream().map(Station::getId).toList();
        Map<UUID, LicenseSummaryResponse> licensesByStationId = stationIds.isEmpty()
                ? Map.of()
                : licenseRepository.findActiveByStationIds(stationIds, Instant.now()).stream()
                .collect(Collectors.toMap(
                        license -> license.getStation().getId(),
                        license -> new LicenseSummaryResponse(
                                license.getPlan(),
                                license.getExpiresAt()
                        )
                ));

        return stations.map(station -> stationMapper.toAdminStationListItemResponse(
                station,
                licensesByStationId.get(station.getId())
        ));
    }

    @Override
    @Transactional(readOnly = true)
    public AdminStationDetailResponse getAdminStationDetail(UUID stationId) {
        Station station = stationRepository.findAdminDetailById(stationId)
                .orElseThrow(() -> new AppException(StationErrorCode.STATION_NOT_FOUND, stationId));

        var licenseOpt = licenseRepository.findActiveByStationId(stationId, Instant.now());
        LicenseSummaryResponse licenseSummary = licenseOpt
                .map(l -> new LicenseSummaryResponse(l.getPlan(), l.getExpiresAt()))
                .orElse(null);

        return stationMapper.toAdminStationDetailResponse(station, licenseSummary);
    }

    @Override
    @Transactional
    public void suspendStation(UUID stationId, String reason) {
        UserProfile admin = currentProfileProvider.requireProfile();
        log.info(LogConstant.SERVICE_LOG_FORMAT, LogConstant.ACTION_START, "suspendStation", admin.getId(), "stationId=" + stationId);
        Station station = stationRepository.findById(stationId)
                .orElseThrow(() -> new AppException(StationErrorCode.STATION_NOT_FOUND, stationId));

        if (station.getStatus() != StationStatus.ACTIVE) {
            throw new AppException(StationErrorCode.INVALID_STATUS_TRANSITION, stationId);
        }

        StationStatus previousStatus = station.getStatus();
        station.setStatus(StationStatus.SUSPENDED);

        stationStatusHistoryService.recordTransition(
                station,
                StationStatusEventType.SUSPENDED,
                previousStatus,
                admin,
                reason
        );
        log.info(LogConstant.SERVICE_LOG_FORMAT, LogConstant.ACTION_SUCCESS, "suspendStation", admin.getId(), "stationId=" + stationId);
    }

    @Override
    @Transactional
    public void reactivateStation(UUID stationId, String reason) {
        UserProfile admin = currentProfileProvider.requireProfile();
        log.info(LogConstant.SERVICE_LOG_FORMAT, LogConstant.ACTION_START, "reactivateStation", admin.getId(), "stationId=" + stationId);
        Station station = stationRepository.findById(stationId)
                .orElseThrow(() -> new AppException(StationErrorCode.STATION_NOT_FOUND, stationId));

        if (station.getStatus() != StationStatus.SUSPENDED) {
            throw new AppException(StationErrorCode.INVALID_STATUS_TRANSITION, stationId);
        }

        StationStatus previousStatus = station.getStatus();
        station.setStatus(StationStatus.ACTIVE);

        stationStatusHistoryService.recordTransition(
                station,
                StationStatusEventType.REACTIVATED,
                previousStatus,
                admin,
                reason
        );
        log.info(LogConstant.SERVICE_LOG_FORMAT, LogConstant.ACTION_SUCCESS, "reactivateStation", admin.getId(), "stationId=" + stationId);
    }

    private Pageable getPageable(int pageNo, int pageSize) {
        int pageIndex = pageNo > 0 ? pageNo - 1 : 0;
        return PageRequest.of(pageIndex, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    private String formatStationCode(long sequence) {
        return "ST-%04d".formatted(sequence);
    }
}
