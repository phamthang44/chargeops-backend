package com.thang.chargeops.station.service;

import com.thang.chargeops.common.enums.EquipmentStatusActorType;
import com.thang.chargeops.common.enums.OperationalChargePointStatus;
import com.thang.chargeops.common.enums.ProvisioningStatus;
import com.thang.chargeops.common.enums.RuntimeStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.entity.ChargePointStatusEvent;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.entity.ConnectorStatusEvent;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.event.EquipmentChangeType;
import com.thang.chargeops.station.event.EquipmentStateChangedEvent;
import com.thang.chargeops.station.repository.ChargePointStatusEventRepository;
import com.thang.chargeops.station.repository.ConnectorStatusEventRepository;
import com.thang.chargeops.station.service.impl.EquipmentStatusHistoryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EquipmentStatusHistoryServiceImplTest {

    @Mock
    private ChargePointStatusEventRepository chargePointStatusEventRepository;

    @Mock
    private ConnectorStatusEventRepository connectorStatusEventRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private com.thang.chargeops.incident.IncidentReasonResolver incidentReasonResolver;

    private EquipmentStatusHistoryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new EquipmentStatusHistoryServiceImpl(
                chargePointStatusEventRepository,
                connectorStatusEventRepository,
                eventPublisher,
                incidentReasonResolver
        );
    }

    @Test
    void recordsTrimmedReasonForChargePointOperationalTransition() {
        ChargePoint chargePoint = mockChargePoint();
        UserProfile performedBy = mock(UserProfile.class);

        service.recordChargePointOperationalTransition(
                chargePoint,
                OperationalChargePointStatus.AVAILABLE,
                OperationalChargePointStatus.MAINTENANCE,
                EquipmentStatusActorType.OWNER,
                performedBy,
                "  Scheduled maintenance  "
        );

        ArgumentCaptor<ChargePointStatusEvent> captor =
                ArgumentCaptor.forClass(ChargePointStatusEvent.class);
        verify(chargePointStatusEventRepository).save(captor.capture());
        assertThat(captor.getValue().getFromStatus()).isEqualTo("AVAILABLE");
        assertThat(captor.getValue().getToStatus()).isEqualTo("MAINTENANCE");
        assertThat(captor.getValue().getReason()).isEqualTo("Scheduled maintenance");
        assertThat(captor.getValue().getPerformedAt()).isNotNull();

        EquipmentStateChangedEvent event = capturePublishedEvent();
        assertThat(event.stationId()).isEqualTo(chargePoint.getStation().getId());
        assertThat(event.chargePointId()).isEqualTo(chargePoint.getId());
        assertThat(event.connectorId()).isNull();
        assertThat(event.from()).isEqualTo("AVAILABLE");
        assertThat(event.to()).isEqualTo("MAINTENANCE");
        assertThat(event.changeType()).isEqualTo(EquipmentChangeType.CHARGE_POINT_OPERATIONAL);
        assertThat(event.occurredAt()).isNotNull();
    }

    @Test
    void recordsProvisioningTransitionWithMatchingEventDimension() {
        ChargePoint chargePoint = mockChargePoint();
        UserProfile performedBy = mock(UserProfile.class);

        service.recordChargePointProvisioningTransition(
                chargePoint,
                ProvisioningStatus.SUSPENDED,
                ProvisioningStatus.ACTIVE,
                EquipmentStatusActorType.STAFF,
                performedBy,
                "Reactivated"
        );

        ArgumentCaptor<ChargePointStatusEvent> captor =
                ArgumentCaptor.forClass(ChargePointStatusEvent.class);
        verify(chargePointStatusEventRepository).save(captor.capture());
        assertThat(captor.getValue().getFromStatus()).isEqualTo("SUSPENDED");
        assertThat(captor.getValue().getToStatus()).isEqualTo("ACTIVE");

        EquipmentStateChangedEvent event = capturePublishedEvent();
        assertThat(event.stationId()).isEqualTo(chargePoint.getStation().getId());
        assertThat(event.connectorId()).isNull();
        assertThat(event.from()).isEqualTo("SUSPENDED");
        assertThat(event.to()).isEqualTo("ACTIVE");
        assertThat(event.changeType()).isEqualTo(EquipmentChangeType.CHARGE_POINT_PROVISIONING);
    }

    @Test
    void recordsSystemManagedInUseTransitionWithoutUserProfile() {
        Connector connector = mockConnector();
        Instant occurredAt = Instant.parse("2026-09-27T04:00:00Z");
        when(connector.getRuntimeStatus()).thenReturn(RuntimeStatus.AVAILABLE);

        service.transitionConnectorRuntimeAsSystem(
                connector,
                RuntimeStatus.IN_USE,
                occurredAt,
                "Booking session started"
        );

        ArgumentCaptor<ConnectorStatusEvent> captor =
                ArgumentCaptor.forClass(ConnectorStatusEvent.class);
        verify(connectorStatusEventRepository).save(captor.capture());
        assertThat(captor.getValue().getActorType()).isEqualTo(EquipmentStatusActorType.SYSTEM);
        assertThat(captor.getValue().getPerformedBy()).isNull();
        assertThat(captor.getValue().getReason()).isEqualTo("Booking session started");
        assertThat(captor.getValue().getPerformedAt()).isEqualTo(occurredAt);
        verify(connector).updateRuntimeStatus(RuntimeStatus.IN_USE);

        EquipmentStateChangedEvent event = capturePublishedEvent();
        assertThat(event.connectorId()).isEqualTo(connector.getId());
        assertThat(event.chargePointId()).isEqualTo(connector.getChargePoint().getId());
        assertThat(event.stationId()).isEqualTo(connector.getChargePoint().getStation().getId());
        assertThat(event.from()).isEqualTo("AVAILABLE");
        assertThat(event.to()).isEqualTo("IN_USE");
        assertThat(event.changeType()).isEqualTo(EquipmentChangeType.CONNECTOR_RUNTIME);
        assertThat(event.occurredAt()).isEqualTo(occurredAt);
    }

    @Test
    void rejectsSystemTransitionWhenRuntimeStatusDoesNotChange() {
        Connector connector = mock(Connector.class);
        Instant occurredAt = Instant.parse("2026-09-27T04:00:00Z");
        when(connector.getRuntimeStatus()).thenReturn(RuntimeStatus.IN_USE);

        assertThatThrownBy(() -> service.transitionConnectorRuntimeAsSystem(
                connector,
                RuntimeStatus.IN_USE,
                occurredAt,
                "Duplicate transition"
        )).isInstanceOfSatisfying(
                AppException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(StationErrorCode.INVALID_STATUS_TRANSITION)
        );

        verify(connector, never()).updateRuntimeStatus(any());
        verify(connectorStatusEventRepository, never()).save(any());
        // any(Object.class) forces the publishEvent(Object) overload actually used in production;
        // plain any() would bind to the deprecated publishEvent(ApplicationEvent) default method.
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void recordsTrimmedReasonForConnectorRuntimeTransition() {
        Connector connector = mockConnector();
        UserProfile performedBy = mock(UserProfile.class);

        service.recordConnectorRuntimeTransition(
                connector,
                RuntimeStatus.AVAILABLE,
                RuntimeStatus.OFFLINE,
                EquipmentStatusActorType.OWNER,
                performedBy,
                "  Cable replacement  "
        );

        ArgumentCaptor<ConnectorStatusEvent> captor =
                ArgumentCaptor.forClass(ConnectorStatusEvent.class);
        verify(connectorStatusEventRepository).save(captor.capture());
        assertThat(captor.getValue().getFromStatus()).isEqualTo(RuntimeStatus.AVAILABLE);
        assertThat(captor.getValue().getToStatus()).isEqualTo(RuntimeStatus.OFFLINE);
        assertThat(captor.getValue().getReason()).isEqualTo("Cable replacement");
        assertThat(captor.getValue().getPerformedAt()).isNotNull();

        EquipmentStateChangedEvent event = capturePublishedEvent();
        assertThat(event.stationId()).isEqualTo(connector.getChargePoint().getStation().getId());
        assertThat(event.connectorId()).isEqualTo(connector.getId());
        assertThat(event.changeType()).isEqualTo(EquipmentChangeType.CONNECTOR_RUNTIME);
    }

    private EquipmentStateChangedEvent capturePublishedEvent() {
        ArgumentCaptor<EquipmentStateChangedEvent> captor =
                ArgumentCaptor.forClass(EquipmentStateChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        return captor.getValue();
    }

    /**
     * Mock relation chain with concrete IDs: the event is built inside the transaction by
     * resolving station/charge point IDs, so a bare proxy chain returning null would fail.
     */
    private ChargePoint mockChargePoint() {
        ChargePoint chargePoint = mock(ChargePoint.class);
        Station station = mock(Station.class);
        when(station.getId()).thenReturn(UUID.randomUUID());
        when(chargePoint.getStation()).thenReturn(station);
        when(chargePoint.getId()).thenReturn(UUID.randomUUID());
        return chargePoint;
    }

    private Connector mockConnector() {
        Connector connector = mock(Connector.class);
        ChargePoint chargePoint = mockChargePoint();
        when(connector.getChargePoint()).thenReturn(chargePoint);
        when(connector.getId()).thenReturn(UUID.randomUUID());
        return connector;
    }
}
