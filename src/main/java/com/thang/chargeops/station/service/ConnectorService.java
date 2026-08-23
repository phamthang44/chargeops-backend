package com.thang.chargeops.station.service;

import com.thang.chargeops.station.dto.chargepoint.request.ChangeRuntimeStatusRequest;
import com.thang.chargeops.station.dto.chargepoint.request.ProvisionConnectorRequest;
import com.thang.chargeops.station.dto.chargepoint.request.UpdateConnectorRequest;
import com.thang.chargeops.station.dto.chargepoint.response.ConnectorDetailResponse;
import com.thang.chargeops.station.dto.chargepoint.response.ConnectorStatusEventResponse;

import java.util.List;
import java.util.UUID;

public interface ConnectorService {

    List<ConnectorDetailResponse> listForAdmin(UUID stationId, UUID chargePointId);
    ConnectorDetailResponse getDetailForAdmin(UUID stationId, UUID chargePointId, UUID connectorId);
    List<ConnectorStatusEventResponse> getStatusHistoryForAdmin(
            UUID stationId,
            UUID chargePointId,
            UUID connectorId
    );

    ConnectorDetailResponse provision(
            UUID stationId,
            UUID chargePointId,
            ProvisionConnectorRequest request
    );

    ConnectorDetailResponse updateForAdmin(
            UUID stationId,
            UUID chargePointId,
            UUID connectorId,
            UpdateConnectorRequest request
    );

    void deleteDraft(
            UUID stationId,
            UUID chargePointId,
            UUID connectorId
    );

    List<ConnectorDetailResponse> listForCurrentOwner(UUID stationId, UUID chargePointId);
    List<ConnectorStatusEventResponse> getStatusHistoryForCurrentOwner(
            UUID stationId,
            UUID chargePointId,
            UUID connectorId
    );

    ConnectorDetailResponse changeRuntimeStatusForCurrentOwner(
            UUID stationId,
            UUID chargePointId,
            UUID connectorId,
            ChangeRuntimeStatusRequest request
    );
}
