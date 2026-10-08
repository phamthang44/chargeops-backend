package com.thang.chargeops.station.staff.service.impl;

import com.thang.chargeops.exception.errorcode.StaffOperationErrorCode;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.common.enums.*;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.incident.ConnectorIncident;
import com.thang.chargeops.incident.ConnectorIncidentRepository;
import com.thang.chargeops.incident.IncidentReasonResolver;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.access.service.StationAccessService;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.entity.ConnectorStatusEvent;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.repository.ChargePointRepository;
import com.thang.chargeops.station.repository.ConnectorRepository;
import com.thang.chargeops.station.service.EquipmentStatusHistoryService;
import com.thang.chargeops.station.staff.dto.StaffChargePointStatusRequest;
import com.thang.chargeops.station.staff.dto.StaffConnectorStatusRequest;
import com.thang.chargeops.station.staff.entity.StaffAssignmentStatus;
import com.thang.chargeops.station.staff.repository.StaffChargePointHistoryRepository;
import com.thang.chargeops.station.staff.repository.StaffConnectorHistoryRepository;
import com.thang.chargeops.station.staff.repository.StationStaffAssignmentRepository;
import com.thang.chargeops.station.staff.service.StaffStationOperationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.thang.chargeops.station.staff.dto.StaffOperationResponses.*;

@Service
@RequiredArgsConstructor
public class StaffStationOperationServiceImpl implements StaffStationOperationService {
    private static final EnumSet<BookingStatus> ACTIVE_SERVICE = EnumSet.of(
            BookingStatus.CONFIRMED, BookingStatus.CHECKED_IN, BookingStatus.CHARGING);

    private final StationAccessService access;
    private final CurrentProfileProvider currentProfile;
    private final UserProfileRepository profiles;
    private final StationStaffAssignmentRepository assignments;
    private final ChargePointRepository chargePoints;
    private final ConnectorRepository connectors;
    private final BookingRepository bookings;
    private final ConnectorIncidentRepository incidents;
    private final IncidentReasonResolver incidentReasons;
    private final EquipmentStatusHistoryService history;
    private final StaffChargePointHistoryRepository chargePointEvents;
    private final StaffConnectorHistoryRepository connectorEvents;

    @Transactional(readOnly = true)
    @Override
    public Overview overview(UUID stationId) {
        Station station = readableStation(stationId);
        List<ChargePoint> points = chargePoints.findByStationIdOrderByChargePointCodeAsc(stationId);
        long connectorCount = points.stream().mapToLong(point -> connectors.countByChargePointId(point.getId())).sum();
        return new Overview(stationId, station.getName(), station.getAddressLine(),
                station.getOperationalStatus(), points.size(), connectorCount);
    }

    @Transactional(readOnly = true)
    @Override
    public List<ChargePointItem> chargePoints(UUID stationId) {
        readableStation(stationId);
        return chargePoints.findByStationIdOrderByChargePointCodeAsc(stationId).stream()
                .map(this::point).toList();
    }

    @Transactional(readOnly = true)
    @Override
    public List<ConnectorItem> connectors(UUID stationId, UUID chargePointId) {
        readablePoint(stationId, chargePointId);
        return connectors.findByChargePointIdOrderByConnectorCodeAsc(chargePointId).stream()
                .map(this::connector).toList();
    }

    @Transactional(readOnly = true)
    @Override
    public Page<HistoryItem> chargePointHistory(UUID stationId, UUID chargePointId, int page, int size) {
        readablePoint(stationId, chargePointId);
        return chargePointEvents.findByChargePoint_Id(chargePointId, historyPage(page, size))
                .map(event -> new HistoryItem(event.getId(), event.getStatusDimension().name(),
                        event.getFromStatus(), event.getToStatus(), event.getReason(), null, null, null,
                        event.getActorType(),
                        event.getPerformedBy().getDisplayName(), event.getPerformedAt()));
    }

    @Transactional(readOnly = true)
    @Override
    public Page<HistoryItem> connectorHistory(UUID stationId, UUID chargePointId, UUID connectorId,
                                              int page, int size) {
        readableConnector(stationId, chargePointId, connectorId);
        Page<ConnectorStatusEvent> events = connectorEvents.findByConnector_Id(connectorId, historyPage(page, size));
        Map<String, IncidentReasonResolver.Resolved> resolved =
                incidentReasons.resolve(events.getContent().stream().map(ConnectorStatusEvent::getReason).toList());
        List<HistoryItem> content = events.getContent().stream().map(event -> {
            IncidentReasonResolver.Resolved reference = resolved.get(event.getReason());
            return new HistoryItem(event.getId(), "RUNTIME", event.getFromStatus().name(),
                    event.getToStatus().name(), event.getReason(),
                    reference != null ? reference.action() : null,
                    reference != null ? reference.incidentId() : null,
                    reference != null ? reference.detail() : null,
                    event.getActorType(),
                    event.getPerformedBy() == null ? "SYSTEM" : event.getPerformedBy().getDisplayName(),
                    event.getPerformedAt());
        }).toList();
        return new PageImpl<>(content, events.getPageable(), events.getTotalElements());
    }

    @Transactional
    @Override
    public ChargePointItem changeChargePoint(UUID stationId, UUID chargePointId,
                                             StaffChargePointStatusRequest request) {
        Station station = writableStation(stationId);
        ChargePoint point = chargePoints.findByIdAndStationIdForUpdate(chargePointId, stationId)
                .orElseThrow(() -> new AppException(StationErrorCode.CHARGE_POINT_NOT_FOUND));
        if (point.getVersion() != request.expectedVersion()) throw new AppException(StaffOperationErrorCode.VERSION_CONFLICT);
        if (point.getProvisioningStatus() != ProvisioningStatus.ACTIVE)
            throw new AppException(StaffOperationErrorCode.INVALID_TRANSITION);
        OperationalChargePointStatus target = request.operationalStatus();
        if (target == null) throw new AppException(StaffOperationErrorCode.INVALID_TRANSITION);
        if (point.getOperationalChargePointStatus() == target) return point(point);
        String reason = reason(request.reason());
        if (target == OperationalChargePointStatus.AVAILABLE && station.getOperationalStatus() != StationOperationalStatus.OPERATING)
            throw new AppException(StaffOperationErrorCode.STATION_UNAVAILABLE);
        List<Connector> children = connectors.findByChargePointIdOrderByConnectorCodeAsc(chargePointId).stream()
                .sorted(Comparator.comparing(Connector::getId))
                .map(child -> connectors.findByIdWithLock(child.getId())
                        .orElseThrow(() -> new AppException(StationErrorCode.CONNECTOR_NOT_FOUND)))
                .toList();
        for (Connector child : children) {
            if (child.getRuntimeStatus() == RuntimeStatus.IN_USE
                    || bookings.existsByConnectorIdAndStatusIn(child.getId(), ACTIVE_SERVICE))
                throw new AppException(StaffOperationErrorCode.ACTIVE_SERVICE);
            if (target == OperationalChargePointStatus.AVAILABLE
                    && incidents.existsByConnectorIdAndStatus(child.getId(), ConnectorIncident.Status.OPEN))
                throw new AppException(StaffOperationErrorCode.INVALID_TRANSITION);
        }
        OperationalChargePointStatus before = point.getOperationalChargePointStatus();
        point.updateOperationalStatus(target);
        history.recordChargePointOperationalTransition(point, before, target, EquipmentStatusActorType.STAFF,
                currentProfile.requireProfile(), reason);
        chargePoints.flush();
        return point(point);
    }

    @Transactional
    @Override
    public ConnectorItem changeConnector(UUID stationId, UUID chargePointId, UUID connectorId,
                                         StaffConnectorStatusRequest request) {
        Station station = writableStation(stationId);
        ChargePoint point = chargePoints.findByIdAndStationIdForUpdate(chargePointId, stationId)
                .orElseThrow(() -> new AppException(StationErrorCode.CHARGE_POINT_NOT_FOUND));
        Connector child = connectors.findByIdWithLock(connectorId)
                .filter(connector -> connector.getChargePoint().getId().equals(point.getId()))
                .orElseThrow(() -> new AppException(StationErrorCode.CONNECTOR_NOT_FOUND));
        if (child.getVersion() != request.expectedVersion()) throw new AppException(StaffOperationErrorCode.VERSION_CONFLICT);
        if (point.getProvisioningStatus() != ProvisioningStatus.ACTIVE
                || child.getRuntimeStatus() == RuntimeStatus.IN_USE
                || request.runtimeStatus() == null || request.runtimeStatus() == RuntimeStatus.IN_USE)
            throw new AppException(StaffOperationErrorCode.INVALID_TRANSITION);
        if (child.getRuntimeStatus() == request.runtimeStatus()) return connector(child);
        String reason = reason(request.reason());
        if (bookings.existsByConnectorIdAndStatusIn(connectorId, ACTIVE_SERVICE))
            throw new AppException(StaffOperationErrorCode.ACTIVE_SERVICE);
        if (request.runtimeStatus() == RuntimeStatus.AVAILABLE) {
            if (station.getOperationalStatus() != StationOperationalStatus.OPERATING
                    || point.getOperationalChargePointStatus() != OperationalChargePointStatus.AVAILABLE)
                throw new AppException(StaffOperationErrorCode.STATION_UNAVAILABLE);
            if (incidents.existsByConnectorIdAndStatus(connectorId, ConnectorIncident.Status.OPEN))
                throw new AppException(StaffOperationErrorCode.INVALID_TRANSITION);
        }
        RuntimeStatus before = child.getRuntimeStatus();
        child.updateRuntimeStatus(request.runtimeStatus());
        history.recordConnectorRuntimeTransition(child, before, child.getRuntimeStatus(),
                EquipmentStatusActorType.STAFF, currentProfile.requireProfile(), reason);
        connectors.flush();
        return connector(child);
    }

    private Station readableStation(UUID stationId) {
        UserProfile actor = currentProfile.requireProfile();
        if (actor.getStatus() != UserStatus.ACTIVE) throw new AppException(StationErrorCode.STATION_ACCESS_DENIED);
        Station station = access.requireActiveStaff(stationId);
        if (station.getStatus() != StationStatus.ACTIVE) throw new AppException(StaffOperationErrorCode.STATION_UNAVAILABLE);
        return station;
    }

    private Station writableStation(UUID stationId) {
        UUID actorId = currentProfile.requireProfileId();
        UserProfile actor = profiles.findByIdWithLock(actorId)
                .orElseThrow(() -> new AppException(StationErrorCode.STATION_ACCESS_DENIED));
        if (actor.getStatus() != UserStatus.ACTIVE) throw new AppException(StationErrorCode.STATION_ACCESS_DENIED);
        assignments.findActiveForUpdate(stationId, actorId, StaffAssignmentStatus.ACTIVE)
                .orElseThrow(() -> new AppException(StationErrorCode.STATION_ACCESS_DENIED));
        return readableStation(stationId);
    }

    private ChargePoint readablePoint(UUID stationId, UUID chargePointId) {
        readableStation(stationId);
        return chargePoints.findByIdAndStationId(chargePointId, stationId)
                .orElseThrow(() -> new AppException(StationErrorCode.CHARGE_POINT_NOT_FOUND));
    }

    private Connector readableConnector(UUID stationId, UUID chargePointId, UUID connectorId) {
        readablePoint(stationId, chargePointId);
        return connectors.findByIdAndChargePointId(connectorId, chargePointId)
                .orElseThrow(() -> new AppException(StationErrorCode.CONNECTOR_NOT_FOUND));
    }

    private PageRequest historyPage(int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw new AppException(StaffOperationErrorCode.INVALID_TRANSITION);
        return PageRequest.of(page - 1, size, Sort.by(Sort.Order.desc("performedAt"), Sort.Order.desc("id")));
    }

    private String reason(String input) {
        if (input == null || input.isBlank() || input.length() > 500)
            throw new AppException(StaffOperationErrorCode.REASON_REQUIRED);
        return input.strip();
    }

    private ChargePointItem point(ChargePoint value) {
        return new ChargePointItem(value.getId(), value.getChargePointCode(), value.getName(), value.getZoneLabel(),
                value.getProvisioningStatus(), value.getOperationalChargePointStatus(), value.getVersion());
    }

    private ConnectorItem connector(Connector value) {
        UUID activeIncidentId = incidents.findFirstByConnectorIdAndStatusOrderByReportedAtDesc(
                value.getId(), ConnectorIncident.Status.OPEN)
                .map(ConnectorIncident::getId)
                .orElse(null);
        return new ConnectorItem(value.getId(), value.getConnectorCode(), value.getConnectorType(),
                value.getRuntimeStatus(), value.getVersion(), activeIncidentId);
    }
}
