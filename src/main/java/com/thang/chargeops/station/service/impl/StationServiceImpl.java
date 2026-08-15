package com.thang.chargeops.station.service.impl;

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
import com.thang.chargeops.station.dto.station.request.RegisterStationRequest;
import com.thang.chargeops.station.dto.station.request.RejectStationRequest;
import com.thang.chargeops.station.dto.station.response.OwnerStationSummaryResponse;
import com.thang.chargeops.station.dto.station.response.StationApprovalDetailResponse;
import com.thang.chargeops.station.dto.station.response.StationApprovalSummaryResponse;
import com.thang.chargeops.station.dto.station.response.StationCreatedResponse;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.mapper.StationMapper;
import com.thang.chargeops.station.policy.StationApprovalPolicy;
import com.thang.chargeops.station.projection.OwnerStationSummaryProjection;
import com.thang.chargeops.station.projection.StationApprovalSummaryProjection;
import com.thang.chargeops.station.repository.LicenseRepository;
import com.thang.chargeops.station.repository.StationRepository;
import com.thang.chargeops.station.service.StationService;
import com.thang.chargeops.station.service.StationStatusHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;


@Service
@RequiredArgsConstructor
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
        Station station = stationRepository.findById(id)
                .orElseThrow(() -> new AppException(StationErrorCode.STATION_NOT_FOUND, id));
        stationApprovalPolicy.requireCanBeApproved(station);

        UserProfile admin = currentProfileProvider.requireProfile();
        StationStatus previousStatus = station.getStatus();
        station.setStatus(StationStatus.ACTIVE);

        stationStatusHistoryService.recordTransition(
                station,
                StationStatusEventType.APPROVED,
                previousStatus,
                admin,
                null
        );
    }

    @Override
    @Transactional
    public void rejectStation(UUID id, RejectStationRequest request) {
        Station station = stationRepository.findById(id)
                .orElseThrow(() -> new AppException(StationErrorCode.STATION_NOT_FOUND, id));
        stationApprovalPolicy.requireCanBeRejected(station, request.getReason());
        UserProfile admin = currentProfileProvider.requireProfile();
        StationStatus previousStatus = station.getStatus();
        station.setStatus(StationStatus.REJECTED);

        stationStatusHistoryService.recordTransition(
                station,
                StationStatusEventType.REJECTED,
                previousStatus,
                admin,
                request.getReason()
        );
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

    private Pageable getPageable(int pageNo, int pageSize) {
        return PageRequest.of(pageNo - 1, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    private String formatStationCode(long sequence) {
        return "ST-%04d".formatted(sequence);
    }
}
