package com.thang.chargeops.station.staff.service;

import com.thang.chargeops.station.staff.dto.StaffChargePointStatusRequest;
import com.thang.chargeops.station.staff.dto.StaffConnectorStatusRequest;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.UUID;

import static com.thang.chargeops.station.staff.dto.StaffOperationResponses.*;

public interface StaffStationOperationService {
    Overview overview(UUID stationId);
    List<ChargePointItem> chargePoints(UUID stationId);
    List<ConnectorItem> connectors(UUID stationId, UUID chargePointId);
    Page<HistoryItem> chargePointHistory(UUID stationId, UUID chargePointId, int page, int size);
    Page<HistoryItem> connectorHistory(UUID stationId, UUID chargePointId, UUID connectorId, int page, int size);
    ChargePointItem changeChargePoint(UUID stationId, UUID chargePointId, StaffChargePointStatusRequest request);
    ConnectorItem changeConnector(UUID stationId, UUID chargePointId, UUID connectorId, StaffConnectorStatusRequest request);
}
