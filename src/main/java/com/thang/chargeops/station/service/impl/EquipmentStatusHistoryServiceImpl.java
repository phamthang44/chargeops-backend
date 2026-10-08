package com.thang.chargeops.station.service.impl;

import com.thang.chargeops.common.enums.EquipmentStatusActorType;
import com.thang.chargeops.common.enums.OperationalChargePointStatus;
import com.thang.chargeops.common.enums.ProvisioningStatus;
import com.thang.chargeops.common.enums.RuntimeStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.incident.IncidentReasonResolver;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.station.dto.chargepoint.response.ChargePointStatusEventResponse;
import com.thang.chargeops.station.dto.chargepoint.response.ConnectorStatusEventResponse;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.entity.ChargePointStatusEvent;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.entity.ConnectorStatusEvent;
import com.thang.chargeops.station.event.EquipmentChangeType;
import com.thang.chargeops.station.event.EquipmentStateChangedEvent;
import com.thang.chargeops.station.repository.ChargePointStatusEventRepository;
import com.thang.chargeops.station.repository.ConnectorStatusEventRepository;
import com.thang.chargeops.station.service.EquipmentStatusHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EquipmentStatusHistoryServiceImpl implements EquipmentStatusHistoryService {

    private final ChargePointStatusEventRepository chargePointStatusEventRepository;
    private final ConnectorStatusEventRepository connectorStatusEventRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final IncidentReasonResolver incidentReasons;

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
        Instant occurredAt = Instant.now();
        chargePointStatusEventRepository.save(ChargePointStatusEvent.provisioningTransition(
                chargePoint,
                fromStatus,
                toStatus,
                actorType,
                performedBy,
                occurredAt,
                reason
        ));
        publishStateChanged(
                chargePoint.getStation().getId(),
                chargePoint.getId(),
                null,
                fromStatus.name(),
                toStatus.name(),
                EquipmentChangeType.CHARGE_POINT_PROVISIONING,
                occurredAt
        );
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
        Instant occurredAt = Instant.now();
        chargePointStatusEventRepository.save(ChargePointStatusEvent.operationalTransition(
                chargePoint,
                fromStatus,
                toStatus,
                actorType,
                performedBy,
                occurredAt,
                reason
        ));
        publishStateChanged(
                chargePoint.getStation().getId(),
                chargePoint.getId(),
                null,
                fromStatus.name(),
                toStatus.name(),
                EquipmentChangeType.CHARGE_POINT_OPERATIONAL,
                occurredAt
        );
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
        Instant occurredAt = Instant.now();
        connectorStatusEventRepository.save(ConnectorStatusEvent.userTransition(
                connector,
                fromStatus,
                toStatus,
                actorType,
                performedBy,
                occurredAt,
                reason
        ));
        publishConnectorChanged(connector, fromStatus.name(), toStatus.name(), occurredAt);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void transitionConnectorRuntimeAsSystem(
            Connector connector,
            RuntimeStatus toStatus,
            Instant occurredAt,
            String reason
    ) {
        Objects.requireNonNull(connector, "connector must not be null");
        Objects.requireNonNull(toStatus, "toStatus must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");

        RuntimeStatus fromStatus = connector.getRuntimeStatus();

        if (fromStatus == toStatus) {
            throw new AppException(StationErrorCode.INVALID_STATUS_TRANSITION);
        }

        connector.updateRuntimeStatus(toStatus);
        connectorStatusEventRepository.save(ConnectorStatusEvent.systemTransition(
                connector,
                fromStatus,
                toStatus,
                occurredAt,
                reason
        ));
        publishConnectorChanged(connector, fromStatus.name(), toStatus.name(), occurredAt);
    }

    /**
     * Publishes the scalar snapshot while still inside the caller's transaction, so station and
     * charge point IDs are resolved eagerly (JPA lazy proxies must not be touched after commit).
     */
    private void publishConnectorChanged(Connector connector, String from, String to, Instant occurredAt) {
        var chargePoint = connector.getChargePoint();
        publishStateChanged(
                chargePoint.getStation().getId(),
                chargePoint.getId(),
                connector.getId(),
                from,
                to,
                EquipmentChangeType.CONNECTOR_RUNTIME,
                occurredAt
        );
    }

    private void publishStateChanged(
            UUID stationId,
            UUID chargePointId,
            UUID connectorId,
            String from,
            String to,
            EquipmentChangeType changeType,
            Instant occurredAt
    ) {
        eventPublisher.publishEvent(new EquipmentStateChangedEvent(
                stationId,
                chargePointId,
                connectorId,
                from,
                to,
                changeType,
                occurredAt
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
        List<ConnectorStatusEvent> events =
                connectorStatusEventRepository.findAllByConnectorIdOrderByPerformedAtAscIdAsc(connectorId);
        Map<String, IncidentReasonResolver.Resolved> resolved =
                incidentReasons.resolve(events.stream().map(ConnectorStatusEvent::getReason).toList());
        return events.stream()
                .map(event -> toResponse(event, resolved.get(event.getReason())))
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

    private ConnectorStatusEventResponse toResponse(ConnectorStatusEvent event, IncidentReasonResolver.Resolved reference) {
        UserProfile actor = event.getPerformedBy();
        return new ConnectorStatusEventResponse(
                event.getId(),
                event.getFromStatus(),
                event.getToStatus(),
                event.getReason(),
                reference != null ? reference.action() : null,
                reference != null ? reference.incidentId() : null,
                reference != null ? reference.detail() : null,
                event.getActorType(),
                actor != null ? actor.getId() : null,
                actor != null ? actor.getDisplayName() : "SYSTEM",
                event.getPerformedAt()
        );
    }
}
