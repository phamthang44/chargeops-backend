package com.thang.chargeops.station.service;

import com.thang.chargeops.station.dto.station.filter.StationFilter;
import com.thang.chargeops.station.dto.station.request.RegisterStationRequest;
import com.thang.chargeops.station.dto.station.request.RejectStationRequest;
import com.thang.chargeops.station.dto.station.request.ChangeStationOperationalStatusRequest;
import com.thang.chargeops.station.dto.station.response.*;
import com.thang.chargeops.station.entity.Station;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface StationService {

    StationCreatedResponse createStationRegistration(RegisterStationRequest request);

    StationApprovalDetailResponse getStationApprovalDetail(UUID stationId);

    void approveStation(UUID id);

    void rejectStation(UUID id, RejectStationRequest request);

    Page<OwnerStationSummaryResponse> getMyStations(int pageNo, int pageSize);

    StationOperationalStatusResponse changeOperationalStatusForCurrentOwner(
            UUID stationId,
            ChangeStationOperationalStatusRequest request
    );

    Page<StationApprovalSummaryResponse> getStationApprovals(int pageNo, int pageSize);

    Station getStationById(UUID stationId);

    Page<AdminStationListItemResponse> getAdminStations(
            int pageNo,
            int pageSize,
            StationFilter filter
    );

    AdminStationDetailResponse getAdminStationDetail(UUID stationId);

    void suspendStation(UUID stationId, String reason);

    void reactivateStation(UUID stationId, String reason);

}
