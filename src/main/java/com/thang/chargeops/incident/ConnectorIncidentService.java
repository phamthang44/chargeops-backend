package com.thang.chargeops.incident;

import com.thang.chargeops.booking.command.BookingCommand;
import com.thang.chargeops.booking.command.BookingCommandOperation;
import com.thang.chargeops.booking.command.BookingCommandPayloadHasher;
import com.thang.chargeops.booking.command.BookingCommandRegistry;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.history.BookingStatusActorType;
import com.thang.chargeops.booking.history.BookingStatusHistoryRecorder;
import com.thang.chargeops.booking.history.BookingStatusReason;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.EquipmentStatusActorType;
import com.thang.chargeops.common.enums.OperationalChargePointStatus;
import com.thang.chargeops.common.enums.ProvisioningStatus;
import com.thang.chargeops.common.enums.RuntimeStatus;
import com.thang.chargeops.common.enums.StationOperationalStatus;
import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.CommandErrorCode;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.exception.errorcode.IncidentErrorCode;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.access.service.StationAccessService;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.repository.ConnectorRepository;
import com.thang.chargeops.station.service.EquipmentStatusHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Connector lock serializes reports, snapshot membership, session resolution and recovery. */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('OWNER', 'STAFF', 'ADMIN')")
public class ConnectorIncidentService {
    private final CurrentProfileProvider currentProfileProvider;
    private final UserProfileRepository profiles;
    private final StationAccessService access;
    private final ConnectorRepository connectors;
    private final ConnectorIncidentRepository incidents;
    private final IncidentBookingRepository affectedBookings;
    private final IncidentBookingReadRepository snapshotReader;
    private final IncidentCommandRepository commands;
    private final BookingRepository bookings;
    private final BookingCommandRegistry bookingCommands;
    private final BookingStatusHistoryRecorder bookingHistory;
    private final EquipmentStatusHistoryService equipmentHistory;
    private final Clock clock;

    @Transactional
    public IncidentResponse report(UUID stationId, UUID connectorId, UUID key, IncidentRequests.Report request) {
        if (request == null) invalid();
        requireKey(key);
        requireVersion(request.expectedConnectorVersion());
        String reason = normalizeReason(request.reason());
        Instant now = clock.instant();
        if (request.occurredAt() == null || request.occurredAt().isAfter(now)) invalid();
        UserProfile actor = actor(true);
        Connector connector = connector(stationId, connectorId, actor, true);
        String hash = hash("REPORT", stationId, connectorId, request.expectedConnectorVersion(), reason, request.occurredAt());
        Optional<ConnectorIncident> replay = replay(actor, IncidentCommand.Operation.REPORT, key, hash);
        if (replay.isPresent()) return response(replay.get());
        if (connector.getVersion() != request.expectedConnectorVersion()) versionConflict(connector.getVersion());
        if (incidents.existsByConnectorIdAndStatus(connectorId, ConnectorIncident.Status.OPEN)) {
            throw new AppException(IncidentErrorCode.ALREADY_OPEN);
        }

        ConnectorIncident incident = incidents.saveAndFlush(ConnectorIncident.report(
                connector, actor, actorType(connector, actor), reason, request.occurredAt(), now));
        transition(connector, RuntimeStatus.OFFLINE, actor,
                IncidentAuditReason.format(IncidentAuditReason.Action.INCIDENT_REPORT, incident.getId(), null, incident.getReason()));
        affectedBookings.saveAll(snapshotReader.findAffected(connectorId, request.occurredAt()).stream()
                .map(booking -> IncidentBooking.snapshot(incident, booking)).toList());
        record(actor, incident, IncidentCommand.Operation.REPORT, key, hash, now);
        connectors.flush();
        return response(incident);
    }

    @Transactional(readOnly = true)
    public IncidentResponse get(UUID stationId, UUID incidentId) {
        UserProfile actor = actor(false);
        UUID connectorId = incidents.findConnectorId(incidentId).orElseThrow(ConnectorIncidentService::notFound);
        connector(stationId, connectorId, actor, false);
        return response(incidents.findById(incidentId).orElseThrow(ConnectorIncidentService::notFound));
    }

    @Transactional
    public IncidentResponse recover(UUID stationId, UUID incidentId, UUID key, IncidentRequests.Recover request) {
        if (request == null) invalid();
        requireKey(key);
        requireVersion(request.expectedVersion());
        String reason = normalizeReason(request.reason());
        UserProfile actor = actor(true);
        UUID connectorId = incidents.findConnectorId(incidentId).orElseThrow(ConnectorIncidentService::notFound);
        Connector connector = connector(stationId, connectorId, actor, true);
        String hash = hash("RECOVER", stationId, incidentId, request.expectedVersion(), reason);
        Optional<ConnectorIncident> replay = replay(actor, IncidentCommand.Operation.RECOVER, key, hash);
        if (replay.isPresent()) return response(replay.get());
        ConnectorIncident incident = incidents.findById(incidentId).orElseThrow(ConnectorIncidentService::notFound);
        if (incident.getVersion() != request.expectedVersion()) versionConflict(incident.getVersion());
        if (incident.getStatus() != ConnectorIncident.Status.OPEN) stateConflict();
        Instant now = clock.instant();
        incident.recover(actor, actorType(connector, actor), reason, now);
        incidents.flush();
        transition(connector, recoveredRuntime(connector), actor,
                IncidentAuditReason.format(IncidentAuditReason.Action.INCIDENT_RECOVER, incidentId, null, reason));
        record(actor, incident, IncidentCommand.Operation.RECOVER, key, hash, now);
        connectors.flush();
        return response(incident);
    }

    @Transactional
    public IncidentResponse resolveSession(UUID stationId, UUID incidentId, UUID bookingId, UUID key,
            IncidentRequests.ResolveSession request) {
        if (request == null) invalid();
        requireKey(key);
        requireVersion(request.expectedBookingVersion());
        String reason = normalizeReason(request.reason());
        if (!Boolean.TRUE.equals(request.safetyConfirmed())) invalid();
        UserProfile actor = actor(true);
        UUID connectorId = incidents.findConnectorId(incidentId).orElseThrow(ConnectorIncidentService::notFound);
        Connector connector = connector(stationId, connectorId, actor, true);
        String hash = hash("RESOLVE_SESSION", stationId, incidentId, bookingId, request.expectedBookingVersion(), reason, true);
        Optional<ConnectorIncident> replay = replay(actor, IncidentCommand.Operation.RESOLVE_SESSION, key, hash);
        if (replay.isPresent()) return response(replay.get());
        Booking booking = bookings.findByIdWithLock(bookingId).orElseThrow(ConnectorIncidentService::notFound);
        IncidentBooking affected = affectedBookings.findByIncidentIdAndBookingId(incidentId, bookingId)
                .orElseThrow(ConnectorIncidentService::notFound);
        if (!Objects.equals(booking.getConnector().getId(), connectorId)) throw notFound();
        if (booking.getVersion() != request.expectedBookingVersion()) versionConflict(booking.getVersion());
        if (affected.getState() != IncidentBooking.State.AWAITING_SESSION_STOP) stateConflict();
        Instant now = clock.instant();
        if (IncidentBooking.active(booking.getStatus())) {
            if (booking.getCheckedInAt() == null || now.isBefore(booking.getCheckedInAt())
                    || (booking.getChargingStartedAt() != null && now.isBefore(booking.getChargingStartedAt()))) stateConflict();
            BookingCommand command = bookingCommands.recordSuccess(actor, BookingCommandOperation.RESOLVE_INCIDENT_SESSION,
                    key, hash, booking, now);
            bookingHistory.recordUserTransition(command, BookingStatusActorType.valueOf(actorType(connector, actor).name()),
                    BookingStatusReason.INCIDENT_SESSION_STOPPED, now, current -> current.complete(now));
        } else if (booking.getStatus() != BookingStatus.COMPLETED && booking.getStatus() != BookingStatus.CANCELLED
                && booking.getStatus() != BookingStatus.EXPIRED) {
            stateConflict();
        }
        affected.resolve(actor, reason, now);
        // Safety resolution never grants money or makes the hardware AVAILABLE.
        // If recovery preceded resolution, leave OFFLINE for an explicit runtime acknowledgement.
        transition(connector, RuntimeStatus.OFFLINE, actor,
                IncidentAuditReason.format(IncidentAuditReason.Action.INCIDENT_SESSION_STOP, incidentId, bookingId, reason));
        record(actor, affected.getIncident(), IncidentCommand.Operation.RESOLVE_SESSION, key, hash, now);
        bookings.flush();
        connectors.flush();
        return response(affected.getIncident());
    }

    private UserProfile actor(boolean lock) {
        UUID id = currentProfileProvider.requireProfileId();
        UserProfile actor = (lock ? profiles.findByIdWithLock(id) : profiles.findById(id))
                .orElseThrow(ConnectorIncidentService::notFound);
        if (actor.getStatus() != UserStatus.ACTIVE) throw new AppException(StationErrorCode.STATION_ACCESS_DENIED);
        return actor;
    }

    private Connector connector(UUID stationId, UUID connectorId, UserProfile actor, boolean lock) {
        if (!isAdmin()) access.requireOwnerOrActiveStaff(stationId);
        Connector connector = (lock ? connectors.findByIdWithLock(connectorId) : connectors.findById(connectorId))
                .orElseThrow(ConnectorIncidentService::notFound);
        if (!Objects.equals(connector.getChargePoint().getStation().getId(), stationId)) throw notFound();
        // Recheck assignment/ownership after waiting for the hardware lock.
        if (!isAdmin()) access.requireOwnerOrActiveStaff(stationId);
        return connector;
    }

    private Optional<ConnectorIncident> replay(UserProfile actor, IncidentCommand.Operation operation, UUID key, String hash) {
        return commands.findByActorIdAndOperationAndRequestKey(actor.getId(), operation, key).map(command -> {
            if (!command.getPayloadHash().equals(hash)) throw new AppException(CommandErrorCode.KEY_REUSED);
            return command.getIncident();
        });
    }

    private void record(UserProfile actor, ConnectorIncident incident, IncidentCommand.Operation operation,
            UUID key, String hash, Instant now) {
        commands.saveAndFlush(IncidentCommand.success(actor, incident, operation, key, hash, now));
    }

    private RuntimeStatus recoveredRuntime(Connector connector) {
        if (incidents.existsByConnectorIdAndStatus(connector.getId(), ConnectorIncident.Status.OPEN)) return RuntimeStatus.OFFLINE;
        if (bookings.existsByConnectorIdAndStatusIn(connector.getId(),
                EnumSet.of(BookingStatus.CHECKED_IN, BookingStatus.CHARGING))) return RuntimeStatus.IN_USE;
        Station station = connector.getChargePoint().getStation();
        if (station.getStatus() != StationStatus.ACTIVE || station.getOperationalStatus() != StationOperationalStatus.OPERATING
                || connector.getChargePoint().getProvisioningStatus() != ProvisioningStatus.ACTIVE
                || connector.getChargePoint().getOperationalChargePointStatus() != OperationalChargePointStatus.AVAILABLE)
            return RuntimeStatus.OFFLINE;
        return RuntimeStatus.AVAILABLE;
    }

    private void transition(Connector connector, RuntimeStatus target, UserProfile actor, String reference) {
        RuntimeStatus before = connector.getRuntimeStatus();
        if (before == target) return;
        connector.updateRuntimeStatus(target);
        equipmentHistory.recordConnectorRuntimeTransition(connector, before, target, actorType(connector, actor), actor, reference);
    }

    private IncidentResponse response(ConnectorIncident incident) {
        Connector connector = incident.getConnector();
        List<IncidentResponse.AffectedBooking> affected = affectedBookings.findByIncidentIdOrderByIdAsc(incident.getId()).stream()
                .map(row -> new IncidentResponse.AffectedBooking(row.getBooking().getId(), row.getSnapshotStatus(), row.getSnapshotVersion(),
                        row.getBooking().getStatus(), row.getBooking().getVersion(), row.getState(), row.getResolvedAt(), row.getResolutionReason()))
                .toList();
        String state = affected.stream().anyMatch(row -> row.safetyState() == IncidentBooking.State.AWAITING_SESSION_STOP)
                ? "PENDING" : "COMPLETED";
        return new IncidentResponse(incident.getId(), connector.getChargePoint().getStation().getId(), connector.getId(),
                incident.getStatus(), incident.getVersion(), connector.getVersion(), connector.getRuntimeStatus(), incident.getReason(),
                incident.getOccurredAt(), incident.getReportedAt(), incident.getRecoveredAt(), incident.getRecoveryReason(),
                affected.stream().map(IncidentResponse.AffectedBooking::bookingId).toList(), state, affected);
    }

    private static boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(role -> role.getAuthority().equals("ROLE_ADMIN"));
    }
    private static EquipmentStatusActorType actorType(Connector connector, UserProfile actor) {
        if (isAdmin()) return EquipmentStatusActorType.ADMIN;
        return Objects.equals(connector.getChargePoint().getStation().getOwner().getId(), actor.getId())
                ? EquipmentStatusActorType.OWNER : EquipmentStatusActorType.STAFF;
    }
    private static String hash(Object... fields) {
        StringBuilder canonical = new StringBuilder("incident-v1");
        for (Object field : fields) {
            String value = Objects.requireNonNull(field).toString();
            canonical.append('\n').append(value.length()).append(':').append(value);
        }
        return BookingCommandPayloadHasher.sha256(canonical.toString());
    }
    private static String normalizeReason(String reason) {
        if (reason == null || reason.strip().length() < 5 || reason.length() > 2000) invalid();
        return java.text.Normalizer.normalize(reason, java.text.Normalizer.Form.NFC).strip();
    }
    private static void requireKey(UUID key) { if (key == null) invalid(); }
    private static void requireVersion(Long version) { if (version == null || version < 0) invalid(); }
    private static void invalid() { throw new AppException(CommonErrorCode.INVALID_REQUEST); }
    private static void stateConflict() { throw new AppException(IncidentErrorCode.STATE_CONFLICT); }
    private static void versionConflict(long version) {
        throw AppException.withDetails(IncidentErrorCode.VERSION_CONFLICT, Map.of("currentVersion", version));
    }
    private static AppException notFound() { return new AppException(CommonErrorCode.RESOURCE_NOT_FOUND); }
}
