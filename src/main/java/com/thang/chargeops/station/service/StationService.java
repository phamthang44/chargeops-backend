package com.thang.chargeops.station.service;

import com.thang.chargeops.station.dto.station.request.RegisterStationRequest;
import com.thang.chargeops.station.dto.station.request.RejectStationRequest;
import com.thang.chargeops.station.dto.station.response.OwnerStationSummaryResponse;
import com.thang.chargeops.station.dto.station.response.StationApprovalDetailResponse;
import com.thang.chargeops.station.dto.station.response.StationApprovalSummaryResponse;
import com.thang.chargeops.station.dto.station.response.StationCreatedResponse;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface StationService {

    StationCreatedResponse createStationRegistration(RegisterStationRequest request);

    StationApprovalDetailResponse getStationApprovalDetail(UUID stationId);

    void approveStation(UUID id);

    void rejectStation(UUID id, RejectStationRequest request);

    Page<OwnerStationSummaryResponse> getMyStations(int pageNo, int pageSize);

    Page<StationApprovalSummaryResponse> getStationApprovals(int pageNo, int pageSize);

}
