package com.thang.chargeops.station.service.impl;

import com.thang.chargeops.exception.errormessage.StationErrorMessage;
import com.thang.chargeops.station.dto.station.filter.StationFilter;
import com.thang.chargeops.station.dto.station.request.ChangeStationOperationalStatusRequest;
import com.thang.chargeops.station.dto.station.request.RegisterStationRequest;
import com.thang.chargeops.station.dto.station.request.RejectStationRequest;
import com.thang.chargeops.station.dto.station.response.AdminStationDetailResponse;
import com.thang.chargeops.station.dto.station.response.AdminStationListItemResponse;
import com.thang.chargeops.station.dto.station.response.OwnerStationSummaryResponse;
import com.thang.chargeops.station.dto.station.response.StationApprovalDetailResponse;
import com.thang.chargeops.station.dto.station.response.StationApprovalSummaryResponse;
import com.thang.chargeops.station.dto.station.response.StationCreatedResponse;
import com.thang.chargeops.station.dto.station.response.StationOperationalStatusResponse;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.service.StationService;
import com.thang.chargeops.station.service.usecase.AdminStationQueryUseCase;
import com.thang.chargeops.station.service.usecase.OwnerStationUseCase;
import com.thang.chargeops.station.service.usecase.StationLifecycleUseCase;
import com.thang.chargeops.station.service.usecase.StationRegistrationUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Stable facade for existing controllers and services. Business logic lives in
 * focused use-case services so each dependency list has one clear purpose.
 */
@Service
@RequiredArgsConstructor
public class StationServiceImpl implements StationService {

    private final StationRegistrationUseCase registrationUseCase;
    private final StationLifecycleUseCase lifecycleUseCase;
    private final OwnerStationUseCase ownerUseCase;
    private final AdminStationQueryUseCase adminQueryUseCase;

    @Override
    public StationCreatedResponse createStationRegistration(
            RegisterStationRequest request
    ) {
        return registrationUseCase.create(request);
    }

    @Override
    public StationApprovalDetailResponse getStationApprovalDetail(UUID stationId) {
        return lifecycleUseCase.getApprovalDetail(stationId);
    }

    @Override
    public void approveStation(UUID stationId) {
        lifecycleUseCase.approve(stationId);
    }

    @Override
    public void rejectStation(UUID stationId, RejectStationRequest request) {
        lifecycleUseCase.reject(stationId, request);
    }

    @Override
    public Page<OwnerStationSummaryResponse> getMyStations(
            int pageNo,
            int pageSize
    ) {
        return ownerUseCase.getMyStations(getPageable(pageNo, pageSize));
    }

    @Override
    public StationOperationalStatusResponse changeOperationalStatusForCurrentOwner(
            UUID stationId,
            ChangeStationOperationalStatusRequest request
    ) {
        return ownerUseCase.changeOperationalStatus(stationId, request);
    }

    @Override
    public Page<StationApprovalSummaryResponse> getStationApprovals(
            int pageNo,
            int pageSize
    ) {
        return lifecycleUseCase.getApprovals(getPageable(pageNo, pageSize));
    }

    @Override
    public Station getStationById(UUID stationId) {
        return adminQueryUseCase.getById(stationId);
    }

    @Override
    public Page<AdminStationListItemResponse> getAdminStations(
            int pageNo,
            int pageSize,
            StationFilter filter
    ) {
        return adminQueryUseCase.getStations(
                getPageable(pageNo, pageSize),
                filter
        );
    }

    @Override
    public AdminStationDetailResponse getAdminStationDetail(UUID stationId) {
        return adminQueryUseCase.getDetail(stationId);
    }

    @Override
    public void suspendStation(UUID stationId, String reason) {
        lifecycleUseCase.suspend(stationId, reason);
    }

    @Override
    public void reactivateStation(UUID stationId, String reason) {
        lifecycleUseCase.reactivate(stationId, reason);
    }

    private Pageable getPageable(int pageNo, int pageSize) {
        if (pageNo < 1) {
            throw new IllegalArgumentException(
                    StationErrorMessage.PAGE_NUMBER_MIN.defaultMessage()
            );
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException(
                    StationErrorMessage.PAGE_SIZE_MIN.defaultMessage()
            );
        }
        if (pageSize > 100) {
            throw new IllegalArgumentException(
                    StationErrorMessage.PAGE_SIZE_MAX.defaultMessage()
            );
        }
        return PageRequest.of(
                pageNo - 1,
                pageSize,
                Sort.by(Sort.Direction.DESC, "createdAt")
        );
    }
}
