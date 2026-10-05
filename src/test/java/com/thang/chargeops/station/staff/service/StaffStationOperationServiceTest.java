package com.thang.chargeops.station.staff.service;

import com.thang.chargeops.station.staff.service.impl.StaffStationOperationServiceImpl;
import com.thang.chargeops.exception.errorcode.StaffOperationErrorCode;

import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.common.enums.*;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.incident.ConnectorIncident;
import com.thang.chargeops.incident.ConnectorIncidentRepository;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.access.service.StationAccessService;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.repository.ChargePointRepository;
import com.thang.chargeops.station.repository.ConnectorRepository;
import com.thang.chargeops.station.service.EquipmentStatusHistoryService;
import com.thang.chargeops.station.staff.dto.StaffChargePointStatusRequest;
import com.thang.chargeops.station.staff.dto.StaffConnectorStatusRequest;
import com.thang.chargeops.station.staff.entity.StaffAssignmentStatus;
import com.thang.chargeops.station.staff.entity.StationStaffAssignment;
import com.thang.chargeops.station.staff.repository.StaffChargePointHistoryRepository;
import com.thang.chargeops.station.staff.repository.StaffConnectorHistoryRepository;
import com.thang.chargeops.station.staff.repository.StationStaffAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StaffStationOperationServiceTest {
    @Mock StationAccessService access;
    @Mock CurrentProfileProvider currentProfile;
    @Mock UserProfileRepository profiles;
    @Mock StationStaffAssignmentRepository assignments;
    @Mock ChargePointRepository chargePoints;
    @Mock ConnectorRepository connectors;
    @Mock BookingRepository bookings;
    @Mock ConnectorIncidentRepository incidents;
    @Mock EquipmentStatusHistoryService history;
    @Mock
    StaffChargePointHistoryRepository chargePointEvents;
    @Mock
    StaffConnectorHistoryRepository connectorEvents;
    @InjectMocks StaffStationOperationServiceImpl service;

    UUID stationId;
    UUID pointId;
    UUID connectorId;
    UUID actorId;
    Station station;
    UserProfile actor;
    ChargePoint point;
    Connector connector;

    @BeforeEach
    void setUp() {
        stationId = UUID.randomUUID();
        pointId = UUID.randomUUID();
        connectorId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        station = Station.builder().name("Test station").addressLine("Station address")
                .status(StationStatus.ACTIVE).operationalStatus(StationOperationalStatus.OPERATING).build();
        station.setId(stationId);
        actor = UserProfile.builder().status(UserStatus.ACTIVE).build();
        actor.setId(actorId);
        point = ChargePoint.create(station, "CP-01", "Bay 1", "A", new BigDecimal("22.00"));
        point.setId(pointId);
        point.activate();
        connector = Connector.create(point, "C-01", ConnectorType.TYPE2, new BigDecimal("22.00"), ChargerType.AC);
        connector.setId(connectorId);
    }

    private void writable() {
        when(currentProfile.requireProfileId()).thenReturn(actorId);
        when(currentProfile.requireProfile()).thenReturn(actor);
        when(profiles.findByIdWithLock(actorId)).thenReturn(Optional.of(actor));
        when(assignments.findActiveForUpdate(stationId, actorId, StaffAssignmentStatus.ACTIVE))
                .thenReturn(Optional.of(mock(StationStaffAssignment.class)));
        when(access.requireActiveStaff(stationId)).thenReturn(station);
    }

    @Test
    void staffMutationWritesStaffAudit() {
        writable();
        when(chargePoints.findByIdAndStationIdForUpdate(pointId, stationId)).thenReturn(Optional.of(point));
        when(connectors.findByChargePointIdOrderByConnectorCodeAsc(pointId)).thenReturn(List.of());

        var result = service.changeChargePoint(stationId, pointId,
                new StaffChargePointStatusRequest(OperationalChargePointStatus.OFFLINE, 0L, "Inspect cable"));

        assertThat(result.operationalStatus()).isEqualTo(OperationalChargePointStatus.OFFLINE);
        verify(history).recordChargePointOperationalTransition(eq(point), eq(OperationalChargePointStatus.AVAILABLE),
                eq(OperationalChargePointStatus.OFFLINE), eq(EquipmentStatusActorType.STAFF), eq(actor), eq("Inspect cable"));
    }

    @Test
    void activeBookingBlocksConnectorMutationAndAudit() {
        writable();
        when(chargePoints.findByIdAndStationIdForUpdate(pointId, stationId)).thenReturn(Optional.of(point));
        when(connectors.findByIdWithLock(connectorId)).thenReturn(Optional.of(connector));
        when(bookings.existsByConnectorIdAndStatusIn(eq(connectorId), anyCollection())).thenReturn(true);

        assertThatThrownBy(() -> service.changeConnector(stationId, pointId, connectorId,
                new StaffConnectorStatusRequest(RuntimeStatus.OFFLINE, 0L, "Cable damaged")))
                .isInstanceOf(AppException.class).extracting("errorCode")
                .isEqualTo(StaffOperationErrorCode.ACTIVE_SERVICE);
        verifyNoInteractions(history);
    }

    @Test
    void openIncidentBlocksManualRestore() {
        writable();
        connector.updateRuntimeStatus(RuntimeStatus.OFFLINE);
        when(chargePoints.findByIdAndStationIdForUpdate(pointId, stationId)).thenReturn(Optional.of(point));
        when(connectors.findByIdWithLock(connectorId)).thenReturn(Optional.of(connector));
        when(incidents.existsByConnectorIdAndStatus(connectorId, ConnectorIncident.Status.OPEN)).thenReturn(true);

        assertThatThrownBy(() -> service.changeConnector(stationId, pointId, connectorId,
                new StaffConnectorStatusRequest(RuntimeStatus.AVAILABLE, 0L, "Manual health check")))
                .isInstanceOf(AppException.class).extracting("errorCode")
                .isEqualTo(StaffOperationErrorCode.INVALID_TRANSITION);
        verifyNoInteractions(history);
    }

    @Test
    void revokedStaffCannotReachHardwareLock() {
        when(currentProfile.requireProfileId()).thenReturn(actorId);
        when(profiles.findByIdWithLock(actorId)).thenReturn(Optional.of(actor));
        when(assignments.findActiveForUpdate(stationId, actorId, StaffAssignmentStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeChargePoint(stationId, pointId,
                new StaffChargePointStatusRequest(OperationalChargePointStatus.OFFLINE, 0L, "Inspect cable")))
                .isInstanceOf(AppException.class);
        verifyNoInteractions(chargePoints, connectors, history);
    }

    @Test
    void suspendedStaffCannotReachAssignmentOrHardware() {
        actor.setStatus(UserStatus.SUSPENDED);
        when(currentProfile.requireProfileId()).thenReturn(actorId);
        when(profiles.findByIdWithLock(actorId)).thenReturn(Optional.of(actor));

        assertThatThrownBy(() -> service.changeChargePoint(stationId, pointId,
                new StaffChargePointStatusRequest(OperationalChargePointStatus.OFFLINE, 0L, "Inspect")))
                .isInstanceOf(AppException.class);
        verifyNoInteractions(assignments, chargePoints, connectors, history);
    }

    @Test
    void staleVersionCannotWriteAudit() {
        writable();
        when(chargePoints.findByIdAndStationIdForUpdate(pointId, stationId)).thenReturn(Optional.of(point));

        assertThatThrownBy(() -> service.changeChargePoint(stationId, pointId,
                new StaffChargePointStatusRequest(OperationalChargePointStatus.OFFLINE, 9L, "Inspect")))
                .isInstanceOf(AppException.class).extracting("errorCode")
                .isEqualTo(StaffOperationErrorCode.VERSION_CONFLICT);
        verifyNoInteractions(connectors, history);
    }

    @Test
    void matchingStatusIsNoOpWithoutAudit() {
        writable();
        when(chargePoints.findByIdAndStationIdForUpdate(pointId, stationId)).thenReturn(Optional.of(point));

        var result = service.changeChargePoint(stationId, pointId,
                new StaffChargePointStatusRequest(OperationalChargePointStatus.AVAILABLE, 0L, null));

        assertThat(result.operationalStatus()).isEqualTo(OperationalChargePointStatus.AVAILABLE);
        verifyNoInteractions(connectors, bookings, incidents, history);
    }

    @Test
    void inUseConnectorBlocksChargePointMutation() {
        writable();
        connector.updateRuntimeStatus(RuntimeStatus.IN_USE);
        when(chargePoints.findByIdAndStationIdForUpdate(pointId, stationId)).thenReturn(Optional.of(point));
        when(connectors.findByChargePointIdOrderByConnectorCodeAsc(pointId)).thenReturn(List.of(connector));
        when(connectors.findByIdWithLock(connectorId)).thenReturn(Optional.of(connector));

        assertThatThrownBy(() -> service.changeChargePoint(stationId, pointId,
                new StaffChargePointStatusRequest(OperationalChargePointStatus.MAINTENANCE, 0L, "Inspect")))
                .isInstanceOf(AppException.class).extracting("errorCode")
                .isEqualTo(StaffOperationErrorCode.ACTIVE_SERVICE);
        verifyNoInteractions(history);
    }

    @Test
    void foreignConnectorCannotBeMutated() {
        writable();
        Station otherStation = Station.builder().name("Other").build();
        ChargePoint otherPoint = ChargePoint.create(otherStation, "CP-02", "Other bay", "B", new BigDecimal("22"));
        otherPoint.setId(UUID.randomUUID());
        Connector foreign = Connector.create(otherPoint, "C-02", ConnectorType.TYPE2, new BigDecimal("22"), ChargerType.AC);
        foreign.setId(connectorId);
        when(chargePoints.findByIdAndStationIdForUpdate(pointId, stationId)).thenReturn(Optional.of(point));
        when(connectors.findByIdWithLock(connectorId)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.changeConnector(stationId, pointId, connectorId,
                new StaffConnectorStatusRequest(RuntimeStatus.OFFLINE, 0L, "Inspect")))
                .isInstanceOf(AppException.class);
        verifyNoInteractions(bookings, incidents, history);
    }
}
