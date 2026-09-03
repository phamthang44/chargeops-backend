package com.thang.chargeops.station.service.usecase;

import com.thang.chargeops.common.constant.LogConstant;
import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.common.enums.StationStatusEventType;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.ApprovalErrorCode;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.dto.station.request.RejectStationRequest;
import com.thang.chargeops.station.dto.station.response.StationApprovalDetailResponse;
import com.thang.chargeops.station.dto.station.response.StationApprovalSummaryResponse;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.mapper.StationMapper;
import com.thang.chargeops.station.policy.StationApprovalPolicy;
import com.thang.chargeops.station.projection.StationApprovalSummaryProjection;
import com.thang.chargeops.station.repository.LicenseRepository;
import com.thang.chargeops.station.repository.StationOperatingScheduleRepository;
import com.thang.chargeops.station.repository.StationRepository;
import com.thang.chargeops.station.service.StationStatusHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class StationLifecycleUseCase {

    private static final String LOG_STATION_ID_FORMAT = "stationId=";

    private final StationRepository stationRepository;
    private final StationMapper stationMapper;
    private final CurrentProfileProvider currentProfileProvider;
    private final StationStatusHistoryService stationStatusHistoryService;
    private final StationApprovalPolicy stationApprovalPolicy;
    private final LicenseRepository licenseRepository;
    private final StationOperatingScheduleRepository scheduleRepository;

    @Transactional(readOnly = true)
    public StationApprovalDetailResponse getApprovalDetail(UUID stationId) {
        Station station = stationRepository.findApprovalDetailById(stationId)
                .orElseThrow(() -> new AppException(
                        StationErrorCode.STATION_NOT_FOUND,
                        stationId
                ));
        if (station.getStatus() != StationStatus.PENDING_APPROVAL) {
            throw new AppException(
                    ApprovalErrorCode.STATION_NOT_PENDING_APPROVAL,
                    station.getId(),
                    station.getStatus()
            );
        }

        boolean licenseSubmitted = licenseRepository
                .existsActiveLicenseForStation(stationId, Instant.now());
        return stationMapper.toStationApprovalDetailResponse(
                station,
                licenseSubmitted
        );
    }

    @Transactional
    public void approve(UUID stationId) {
        UserProfile admin = currentProfileProvider.requireProfile();
        logStart("approveStation", admin, stationId);

        Station station = requireStation(stationId);
        stationApprovalPolicy.requireCanBeApproved(station);
        requireActiveOperatingSchedule(stationId, Instant.now());

        StationStatus previousStatus = station.getStatus();
        station.setStatus(StationStatus.ACTIVE);
        stationStatusHistoryService.recordTransition(
                station,
                StationStatusEventType.APPROVED,
                previousStatus,
                admin,
                null
        );
        log.info(
                LogConstant.SERVICE_LOG_FORMAT,
                LogConstant.ACTION_SUCCESS,
                "approveStation",
                admin.getId(),
                LOG_STATION_ID_FORMAT + stationId
                        + ", status=" + station.getStatus()
        );
    }

    @Transactional
    public void reject(UUID stationId, RejectStationRequest request) {
        UserProfile admin = currentProfileProvider.requireProfile();
        log.info(
                LogConstant.SERVICE_LOG_FORMAT,
                LogConstant.ACTION_START,
                "rejectStation",
                admin.getId(),
                LOG_STATION_ID_FORMAT + stationId
                        + ", reason=" + request.getReason()
        );

        Station station = requireStation(stationId);
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
        log.info(
                LogConstant.SERVICE_LOG_FORMAT,
                LogConstant.ACTION_SUCCESS,
                "rejectStation",
                admin.getId(),
                LOG_STATION_ID_FORMAT + stationId
                        + ", status=" + station.getStatus()
        );
    }

    @Transactional(readOnly = true)
    public Page<StationApprovalSummaryResponse> getApprovals(Pageable pageable) {
        Page<StationApprovalSummaryProjection> stations =
                stationRepository.findStationApprovalSummaries(
                        StationStatus.PENDING_APPROVAL,
                        pageable
                );
        return stations.map(stationMapper::toStationApprovalSummaryResponse);
    }

    @Transactional
    public void suspend(UUID stationId, String reason) {
        UserProfile admin = currentProfileProvider.requireProfile();
        logStart("suspendStation", admin, stationId);
        Station station = requireStation(stationId);

        if (station.getStatus() != StationStatus.ACTIVE) {
            throw new AppException(
                    StationErrorCode.INVALID_STATUS_TRANSITION,
                    station.getStatus(),
                    StationStatus.SUSPENDED
            );
        }
        if (reason == null || reason.isBlank()) {
            throw new AppException(
                    StationErrorCode.STATION_SUSPENSION_REASON_REQUIRED
            );
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
        logSuccess("suspendStation", admin, stationId);
    }

    @Transactional
    public void reactivate(UUID stationId, String reason) {
        UserProfile admin = currentProfileProvider.requireProfile();
        logStart("reactivateStation", admin, stationId);
        Station station = requireStation(stationId);

        if (station.getStatus() != StationStatus.SUSPENDED) {
            throw new AppException(
                    StationErrorCode.INVALID_STATUS_TRANSITION,
                    station.getStatus(),
                    StationStatus.ACTIVE
            );
        }
        if (reason == null || reason.isBlank()) {
            throw new AppException(
                    StationErrorCode.STATION_REACTIVATION_REASON_REQUIRED
            );
        }
        requireActiveOperatingSchedule(stationId, Instant.now());

        StationStatus previousStatus = station.getStatus();
        station.setStatus(StationStatus.ACTIVE);
        stationStatusHistoryService.recordTransition(
                station,
                StationStatusEventType.REACTIVATED,
                previousStatus,
                admin,
                reason
        );
        logSuccess("reactivateStation", admin, stationId);
    }

    private Station requireStation(UUID stationId) {
        return stationRepository.findById(stationId)
                .orElseThrow(() -> new AppException(
                        StationErrorCode.STATION_NOT_FOUND,
                        stationId
                ));
    }

    private void requireActiveOperatingSchedule(UUID stationId, Instant at) {
        if (scheduleRepository.findActiveByStationId(stationId, at).isEmpty()) {
            throw new AppException(
                    StationErrorCode.STATION_OPERATING_SCHEDULE_REQUIRED
            );
        }
    }

    private void logStart(String method, UserProfile admin, UUID stationId) {
        log.info(
                LogConstant.SERVICE_LOG_FORMAT,
                LogConstant.ACTION_START,
                method,
                admin.getId(),
                LOG_STATION_ID_FORMAT + stationId
        );
    }

    private void logSuccess(String method, UserProfile admin, UUID stationId) {
        log.info(
                LogConstant.SERVICE_LOG_FORMAT,
                LogConstant.ACTION_SUCCESS,
                method,
                admin.getId(),
                LOG_STATION_ID_FORMAT + stationId
        );
    }
}
