package com.thang.chargeops.station.service;

import com.thang.chargeops.station.dto.chargepoint.request.ActivateChargePointRequest;
import com.thang.chargeops.station.dto.chargepoint.request.ChangeOperationalStatusRequest;
import com.thang.chargeops.station.dto.chargepoint.request.ProvisionChargePointRequest;
import com.thang.chargeops.station.dto.chargepoint.request.UpdateChargePointRequest;
import com.thang.chargeops.station.dto.chargepoint.response.ChargePointDetailResponse;
import com.thang.chargeops.station.dto.chargepoint.response.ChargePointStatusEventResponse;

import java.util.List;
import java.util.UUID;

public interface ChargePointService {

    ChargePointDetailResponse provision(UUID stationId, ProvisionChargePointRequest request);

    List<ChargePointDetailResponse> listForAdmin(UUID stationId);
    ChargePointDetailResponse getDetailForAdmin(UUID stationId, UUID chargePointId);
    List<ChargePointStatusEventResponse> getStatusHistoryForAdmin(UUID stationId, UUID chargePointId);

    ChargePointDetailResponse update(UUID stationId, UUID chargePointId, UpdateChargePointRequest request);

    ChargePointDetailResponse activate(
            UUID stationId,
            UUID chargePointId,
            ActivateChargePointRequest request
    );

    ChargePointDetailResponse suspend(UUID stationId, UUID chargePointId, String reason);
    void deleteDraft(UUID stationId, UUID chargePointId);


    ChargePointDetailResponse reactivate(UUID stationId, UUID chargePointId, String reason);

    List<ChargePointDetailResponse> listForCurrentOwner(UUID stationId);
    List<ChargePointStatusEventResponse> getStatusHistoryForCurrentOwner(UUID stationId, UUID chargePointId);

    ChargePointDetailResponse updateForCurrentOwner(
            UUID stationId,
            UUID chargePointId,
            UpdateChargePointRequest request
    );

    ChargePointDetailResponse changeOperationalStatusForCurrentOwner(
            UUID stationId,
            UUID chargePointId,
            ChangeOperationalStatusRequest request
    );
}
