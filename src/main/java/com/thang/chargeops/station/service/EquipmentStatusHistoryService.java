package com.thang.chargeops.station.service;

import com.thang.chargeops.common.enums.EquipmentStatusActorType;
import com.thang.chargeops.common.enums.OperationalChargePointStatus;
import com.thang.chargeops.common.enums.ProvisioningStatus;
import com.thang.chargeops.common.enums.RuntimeStatus;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.station.dto.chargepoint.response.ChargePointStatusEventResponse;
import com.thang.chargeops.station.dto.chargepoint.response.ConnectorStatusEventResponse;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.entity.Connector;

import java.util.List;
import java.util.UUID;

public interface EquipmentStatusHistoryService {

    void recordChargePointProvisioningTransition(
            ChargePoint chargePoint,
            ProvisioningStatus fromStatus,
            ProvisioningStatus toStatus,
            EquipmentStatusActorType actorType,
            UserProfile performedBy,
            String reason
    );

    void recordChargePointOperationalTransition(
            ChargePoint chargePoint,
            OperationalChargePointStatus fromStatus,
            OperationalChargePointStatus toStatus,
            EquipmentStatusActorType actorType,
            UserProfile performedBy,
            String reason
    );

    void recordConnectorRuntimeTransition(
            Connector connector,
            RuntimeStatus fromStatus,
            RuntimeStatus toStatus,
            EquipmentStatusActorType actorType,
            UserProfile performedBy,
            String reason
    );

    /** TODO(T19): call this from booking/session transitions to and from IN_USE. */
    void recordConnectorSystemRuntimeTransition(
            Connector connector,
            RuntimeStatus fromStatus,
            RuntimeStatus toStatus,
            String reason
    );

    List<ChargePointStatusEventResponse> getChargePointHistory(UUID chargePointId);

    List<ConnectorStatusEventResponse> getConnectorHistory(UUID connectorId);
}
