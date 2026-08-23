package com.thang.chargeops.station.service.impl;

import com.thang.chargeops.common.enums.EquipmentStatusActorType;
import com.thang.chargeops.common.enums.OperationalChargePointStatus;
import com.thang.chargeops.common.enums.ProvisioningStatus;
import com.thang.chargeops.common.enums.RuntimeStatus;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.station.dto.chargepoint.response.ChargePointStatusEventResponse;
import com.thang.chargeops.station.dto.chargepoint.response.ConnectorStatusEventResponse;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.entity.ChargePointStatusEvent;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.entity.ConnectorStatusEvent;
import com.thang.chargeops.station.repository.ChargePointStatusEventRepository;
import com.thang.chargeops.station.repository.ConnectorStatusEventRepository;
import com.thang.chargeops.station.service.EquipmentStatusHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EquipmentStatusHistoryServiceImpl implements EquipmentStatusHistoryService {

    private final ChargePointStatusEventRepository chargePointStatusEventRepository;
    private final ConnectorStatusEventRepository connectorStatusEventRepository;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordChargePointProvisioningTransition(
            ChargePoint chargePoint,
            ProvisioningStatus fromStatus,
            ProvisioningStatus toStatus,
            EquipmentStatusActorType actorType,
            UserProfile performedBy,
            String reason
    ) {
        chargePointStatusEventRepository.save(ChargePointStatusEvent.provisioningTransition(
                chargePoint,
                fromStatus,
                toStatus,
                actorType,
                performedBy,
                Instant.now(),
                reason
        ));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordChargePointOperationalTransition(
            ChargePoint chargePoint,
            OperationalChargePointStatus fromStatus,
            OperationalChargePointStatus toStatus,
            EquipmentStatusActorType actorType,
            UserProfile performedBy,
            String reason
    ) {
        chargePointStatusEventRepository.save(ChargePointStatusEvent.operationalTransition(
                chargePoint,
                fromStatus,
                toStatus,
                actorType,
                performedBy,
                Instant.now(),
                reason
        ));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordConnectorRuntimeTransition(
            Connector connector,
            RuntimeStatus fromStatus,
            RuntimeStatus toStatus,
            EquipmentStatusActorType actorType,
            UserProfile performedBy,
            String reason
    ) {
        connectorStatusEventRepository.save(ConnectorStatusEvent.userTransition(
                connector,
                fromStatus,
                toStatus,
                actorType,
                performedBy,
                Instant.now(),
                reason
        ));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordConnectorSystemRuntimeTransition(
            Connector connector,
            RuntimeStatus fromStatus,
            RuntimeStatus toStatus,
            String reason
    ) {
        connectorStatusEventRepository.save(ConnectorStatusEvent.systemTransition(
                connector,
                fromStatus,
                toStatus,
                Instant.now(),
                reason
        ));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChargePointStatusEventResponse> getChargePointHistory(UUID chargePointId) {
        return chargePointStatusEventRepository
                .findAllByChargePointIdOrderByPerformedAtAscIdAsc(chargePointId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConnectorStatusEventResponse> getConnectorHistory(UUID connectorId) {
        return connectorStatusEventRepository
                .findAllByConnectorIdOrderByPerformedAtAscIdAsc(connectorId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private ChargePointStatusEventResponse toResponse(ChargePointStatusEvent event) {
        UserProfile actor = event.getPerformedBy();
        return new ChargePointStatusEventResponse(
                event.getId(),
                event.getStatusDimension(),
                event.getFromStatus(),
                event.getToStatus(),
                event.getReason(),
                event.getActorType(),
                actor.getId(),
                actor.getDisplayName(),
                event.getPerformedAt()
        );
    }

    private ConnectorStatusEventResponse toResponse(ConnectorStatusEvent event) {
        UserProfile actor = event.getPerformedBy();
        return new ConnectorStatusEventResponse(
                event.getId(),
                event.getFromStatus(),
                event.getToStatus(),
                event.getReason(),
                event.getActorType(),
                actor != null ? actor.getId() : null,
                actor != null ? actor.getDisplayName() : "SYSTEM",
                event.getPerformedAt()
        );
    }
}
