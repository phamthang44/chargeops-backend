package com.thang.chargeops.support.service.impl;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.TicketErrorCode;
import com.thang.chargeops.notification.model.NotificationI18nKey;
import com.thang.chargeops.notification.entity.AppNotification;
import com.thang.chargeops.notification.model.NotificationAudience;
import com.thang.chargeops.notification.model.NotificationContent;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.infra.identity.IdentityRoleService;
import com.thang.chargeops.common.enums.Role;
import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.notification.model.NotificationEventType;
import com.thang.chargeops.profile.model.PrimaryRole;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.notification.service.NotificationService;
import com.thang.chargeops.station.staff.entity.StaffAssignmentStatus;
import com.thang.chargeops.station.staff.repository.StationStaffAssignmentRepository;
import com.thang.chargeops.support.dto.request.AssignTicketRequest;
import com.thang.chargeops.support.dto.request.TicketStatusRequest;
import com.thang.chargeops.support.dto.response.TicketResponse;
import com.thang.chargeops.support.entity.SupportTicket;
import com.thang.chargeops.support.model.TicketStatus;
import com.thang.chargeops.support.repository.SupportTicketRepository;
import com.thang.chargeops.support.repository.TicketEventRepository;
import com.thang.chargeops.support.repository.TicketEscalationRepository;
import com.thang.chargeops.support.repository.TicketFindingRepository;
import com.thang.chargeops.support.entity.TicketEvent;
import com.thang.chargeops.support.service.TicketAccessPolicy;
import com.thang.chargeops.support.service.support.TicketResponseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TicketWorkflowService {
    private static final String ADMIN = "ADMIN";
    private static final String OWNER = "OWNER";
    private static final String REPORTER = "REPORTER";

    private final SupportTicketRepository tickets;
    private final UserProfileRepository profiles;
    private final IdentityRoleService identityRoles;
    private final CurrentProfileProvider currentProfile;
    private final StationStaffAssignmentRepository staffAssignments;
    private final TicketResponseService assembler;
    private final Clock clock;
    private final NotificationService notifications;
    private final TicketEventRepository events;
    private final TicketAccessPolicy access;
    private final TicketEscalationRepository escalations;
    private final TicketFindingRepository findings;

    private SupportTicket locked(UUID id, Long expectedVersion) {
        SupportTicket t = tickets.findByIdForUpdate(id).orElseThrow(() -> new AppException(TicketErrorCode.NOT_FOUND));
        if (expectedVersion != null && !expectedVersion.equals(t.getVersion())) throw new AppException(TicketErrorCode.VERSION_CONFLICT);
        return t;
    }

    private boolean owner(SupportTicket t, UUID actor) {
        return access.isOwner(t, actor);
    }

    private boolean staff(SupportTicket t, UUID actor) {
        return access.isStaff(t, actor);
    }

    private boolean eligible(SupportTicket t, UUID actor) {
        return access.canHandle(t, actor);
    }

    private String kind(SupportTicket t, UUID actor) {
        if (t.getReporter().getId().equals(actor)) return REPORTER;
        if (owner(t, actor)) return OWNER;
        if (staff(t, actor)) return "STAFF";
        return ADMIN;
    }

    private void event(SupportTicket t, UUID actor, String kind, String type, TicketStatus before,
                       UUID oldHandler, String reason) {
        events.saveAndFlush(TicketEvent.create(t, new TicketEvent.EventActor(actor, kind), type,
                before, oldHandler, reason, clock.instant()));
    }

    private TicketResponse saved(SupportTicket t) {
        tickets.saveAndFlush(t);
        return assembler.toResponse(t);
    }

    @Transactional
    public TicketResponse claim(UUID id, long expectedVersion) {
        UserProfile actor = currentProfile.requireProfile();
        if (actor.getStatus() != UserStatus.ACTIVE) throw new AppException(TicketErrorCode.ACCESS_DENIED);
        var scope = tickets.findScope(id).orElseThrow(() -> new AppException(TicketErrorCode.NOT_FOUND));
        UUID stationId = scope.getStationId();
        UUID ownerId = scope.getOwnerId();
        if (stationId != null && !(access.hasRole(OWNER) && actor.getId().equals(ownerId))) {
            staffAssignments.findActiveForUpdate(stationId, actor.getId(), StaffAssignmentStatus.ACTIVE)
                .orElseThrow(() -> new AppException(TicketErrorCode.ACCESS_DENIED));
        }
        SupportTicket t = locked(id, expectedVersion);
        if (!eligible(t, actor.getId())) throw new AppException(TicketErrorCode.ACCESS_DENIED);
        if (t.getStation() != null && escalations.existsByTicket_IdAndResolvedAtIsNull(id))
            throw new AppException(TicketErrorCode.STATE_CONFLICT);
        if (t.getStatus() != TicketStatus.OPEN || t.getAssignedHandler() != null) throw new AppException(TicketErrorCode.STATE_CONFLICT);
        TicketStatus before = t.getStatus();
        t.assign(actor);
        event(t, actor.getId(), kind(t, actor.getId()), "CLAIMED", before, null, null);
        notifyReporterOfAssignment(t, null);
        return saved(t);
    }

    @Transactional
    public TicketResponse assign(UUID id, AssignTicketRequest request) {
        UserProfile actor = currentProfile.requireProfile();
        if (actor.getStatus() != UserStatus.ACTIVE) throw new AppException(TicketErrorCode.ACCESS_DENIED);
        var scope = tickets.findScope(id).orElseThrow(() -> new AppException(TicketErrorCode.NOT_FOUND));
        if (scope.getStationId() == null ? !access.hasRole(ADMIN) :
            !(access.hasRole(OWNER) && actor.getId().equals(scope.getOwnerId())))
            throw new AppException(TicketErrorCode.ACCESS_DENIED);
        if (scope.getStationId() != null && !request.handlerId().equals(scope.getOwnerId())) {
            staffAssignments.findActiveForUpdate(scope.getStationId(), request.handlerId(), StaffAssignmentStatus.ACTIVE)
                .orElseThrow(() -> new AppException(TicketErrorCode.ASSIGNMENT_INVALID));
        }
        SupportTicket t = locked(id, request.expectedVersion());
        requireAssigner(t, actor.getId());
        if (t.getStation() != null && escalations.existsByTicket_IdAndResolvedAtIsNull(id))
            throw new AppException(TicketErrorCode.STATE_CONFLICT);
        boolean platformCase = t.getStation() == null;
        UserProfile handler = assignmentHandler(t, request.handlerId(), platformCase);
        UUID oldHandler = t.getAssignedHandler() == null ? null : t.getAssignedHandler().getId();
        if (handler.getId().equals(oldHandler)) throw new AppException(TicketErrorCode.STATE_CONFLICT);
        TicketStatus before = t.getStatus();
        t.assign(handler);
        event(t, actor.getId(), platformCase ? ADMIN : kind(t, actor.getId()),
                before == TicketStatus.OPEN ? "ASSIGNED" : "REASSIGNED", before, oldHandler, request.reason());
        if (platformCase) {
            notifyAssignedHandler(t, handler, NotificationAudience.ADMIN);
        } else if (handler.getId().equals(t.getStation().getOwner().getId())) {
            notifyAssignedHandler(t, handler, NotificationAudience.OWNER);
        } else if (!platformCase) {
            notifyAssignedHandler(t, handler, NotificationAudience.STAFF);
        }
        notifyReporterOfAssignment(t, actor);
        return saved(t);
    }

    private void notifyAssignedHandler(SupportTicket ticket, UserProfile handler, NotificationAudience audience) {
        String sourceKey = ticket.getId() + ":ASSIGNED:" + handler.getId();
        var content = new NotificationContent(
                NotificationI18nKey.format(NotificationI18nKey.TICKET_ASSIGNED_TITLE,
                        Map.of("code", ticket.getTicketCode())),
                NotificationI18nKey.format(NotificationI18nKey.TICKET_ASSIGNED_BODY,
                        Map.of("code", ticket.getTicketCode())),
                clock.instant());
        notifications.saveIfAbsent(AppNotification.assignedTicket(ticket, audience, sourceKey, content));
    }

    /** The reporter learns who now owns their ticket; assigner is omitted on self-claim. */
    private void notifyReporterOfAssignment(SupportTicket t, UserProfile assigner) {
        var params = new HashMap<String, Object>();
        params.put("code", t.getTicketCode());
        putIfPresent(params, "assignee", displayName(t.getAssignedHandler()));
        if (assigner != null && !assigner.getId().equals(t.getAssignedHandler().getId())) {
            putIfPresent(params, "assigner", displayName(assigner));
        }
        if (t.getStation() != null) putIfPresent(params, "station", t.getStation().getName());
        var content = new NotificationContent(
                NotificationI18nKey.format(NotificationI18nKey.TICKET_ASSIGNED_TITLE,
                        Map.of("code", t.getTicketCode())),
                NotificationI18nKey.format(NotificationI18nKey.TICKET_ASSIGNED_BODY, params),
                clock.instant());
        notifications.saveIfAbsent(AppNotification.assignedToReporter(
                t, t.getId() + ":ASSIGNED:" + t.getAssignedHandler().getId(), content));
    }

    private void putIfPresent(Map<String, Object> params, String key, String value) {
        if (value != null && !value.isBlank()) params.put(key, value);
    }

    private String displayName(UserProfile profile) {
        if (profile == null) return null;
        if (profile.getDisplayName() != null && !profile.getDisplayName().isBlank()) return profile.getDisplayName();
        return profile.getEmail();
    }

    private void requireAssigner(SupportTicket ticket, UUID actorId) {
        if (ticket.getStation() == null ? !access.hasRole(ADMIN) :
                !owner(ticket, actorId)) {
            throw new AppException(TicketErrorCode.ACCESS_DENIED);
        }
    }

    private UserProfile assignmentHandler(SupportTicket t, UUID handlerId, boolean adminActor) {
        if (t.getStatus() != TicketStatus.OPEN && t.getStatus() != TicketStatus.IN_PROGRESS) throw new AppException(TicketErrorCode.STATE_CONFLICT);
        UserProfile handler = profiles.findById(handlerId).orElseThrow(() -> new AppException(TicketErrorCode.ASSIGNMENT_INVALID));
        if (handler.getStatus() != UserStatus.ACTIVE) throw new AppException(TicketErrorCode.ASSIGNMENT_INVALID);
        if (t.getStation() != null && !handler.getId().equals(t.getStation().getOwner().getId()) &&
            !staffAssignments.existsByStation_IdAndStaff_IdAndStatus(t.getStation().getId(), handler.getId(), StaffAssignmentStatus.ACTIVE))
            throw new AppException(TicketErrorCode.ASSIGNMENT_INVALID);
        if (t.getStation() == null && !identityRoles.getRoles(handler.getKeycloakId()).contains(Role.ADMIN))
            throw new AppException(TicketErrorCode.ASSIGNMENT_INVALID);
        if (!adminActor && t.getStation() != null && t.getStatus() == TicketStatus.OPEN &&
            handler.getId().equals(t.getStation().getOwner().getId()))
            throw new AppException(TicketErrorCode.ASSIGNMENT_INVALID);
        return handler;
    }

    @Transactional
    public TicketResponse changeStatus(UUID id, TicketStatusRequest request) {
        UserProfile actor = currentProfile.requireProfile();
        SupportTicket t = locked(id, request.expectedVersion());
        UUID actorId = actor.getId();
        UUID oldHandler = t.getAssignedHandler() == null ? null : t.getAssignedHandler().getId();
        TicketStatus before = t.getStatus();
        if (before == TicketStatus.IN_PROGRESS && request.status() == TicketStatus.RESOLVED) {
            resolveByHandler(t, actorId, oldHandler, request.reason());
        } else if (before == TicketStatus.RESOLVED && request.status() == TicketStatus.CLOSED) {
            confirmByReporter(t, actorId, oldHandler, request.reason());
        } else if (before == TicketStatus.RESOLVED && request.status() == TicketStatus.IN_PROGRESS) {
            continueAfterResolution(t, actorId, oldHandler, request.reason());
        } else throw new AppException(TicketErrorCode.STATE_CONFLICT);
        return saved(t);
    }

    private void resolveByHandler(SupportTicket t, UUID actorId, UUID oldHandler, String reason) {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Resolution reason is required");
        if (!actorId.equals(oldHandler) || !eligible(t, actorId)) throw new AppException(TicketErrorCode.ACCESS_DENIED);
        if (t.getStation() != null) {
            if (escalations.existsByTicket_IdAndResolvedAtIsNull(t.getId())) throw new AppException(TicketErrorCode.STATE_CONFLICT);
            if (t.getBooking() != null) {
                var latestFinding = findings.findFirstByTicket_IdOrderByRecordedAtDescIdDesc(t.getId());
                if (latestFinding.isEmpty() || (t.getResolvedAt() != null
                    && !latestFinding.get().getRecordedAt().isAfter(t.getResolvedAt()))) {
                    throw new AppException(TicketErrorCode.STATE_CONFLICT);
                }
            }
        }
        t.resolve(clock.instant());
        event(t, actorId, kind(t, actorId), "RESOLVED", TicketStatus.IN_PROGRESS, oldHandler, reason);
        notice(t, reason);
    }

    private void confirmByReporter(SupportTicket t, UUID actorId, UUID oldHandler, String reason) {
        if (!t.getReporter().getId().equals(actorId)) throw new AppException(TicketErrorCode.ACCESS_DENIED);
        if (t.getStation() != null && escalations.existsByTicket_IdAndResolvedAtIsNull(t.getId()))
            throw new AppException(TicketErrorCode.STATE_CONFLICT);
        t.close("REPORTER_CONFIRMED");
        event(t, actorId, REPORTER, "REPORTER_CONFIRMED", TicketStatus.RESOLVED, oldHandler, reason);
        notifyTicketClosed(t, reason);
    }

    private void continueAfterResolution(SupportTicket t, UUID actorId, UUID oldHandler, String reason) {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Continuation reason is required");
        if (t.getReporter().getId().equals(actorId)) {
            continueTicket(t, actorId, reason);
        } else if (actorId.equals(oldHandler) && eligible(t, actorId)) {
            t.continueWork(t.getAssignedHandler());
            event(t, actorId, kind(t, actorId), "HANDLER_CONTINUED", TicketStatus.RESOLVED, oldHandler, reason);
            notifyTicketReopened(t, reason);
        } else {
            throw new AppException(TicketErrorCode.ACCESS_DENIED);
        }
    }

    private void continueTicket(SupportTicket t, UUID reporter, String reason) {
        UUID oldHandler = t.getAssignedHandler() == null ? null : t.getAssignedHandler().getId();
        UserProfile handler = oldHandler != null && eligibleHandler(t) ? t.getAssignedHandler() : null;
        t.continueWork(handler);
        event(t, reporter, REPORTER, "REPORTER_CONTINUED", TicketStatus.RESOLVED, oldHandler, reason);
        notifyTicketReopened(t, reason);
    }

    private boolean eligibleHandler(SupportTicket t) {
        UserProfile handler = t.getAssignedHandler();
        if (handler.getStatus() != UserStatus.ACTIVE) return false;
        UUID id = handler.getId();
        if (t.getStation() == null) return identityRoles.getRoles(handler.getKeycloakId()).contains(Role.ADMIN);
        if (id.equals(t.getStation().getOwner().getId()))
            return identityRoles.getRoles(handler.getKeycloakId()).contains(Role.OWNER);
        return staffAssignments.existsByStation_IdAndStaff_IdAndStatus(t.getStation().getId(), id, StaffAssignmentStatus.ACTIVE);
    }

    private void notice(SupportTicket t, String result) {
        String title = NotificationI18nKey.format(
                NotificationI18nKey.TICKET_RESOLVED_TITLE,
                Map.of("code", t.getTicketCode()));
        String body = NotificationI18nKey.format(
                NotificationI18nKey.TICKET_RESOLVED_BODY,
                Map.of(
                        "code", t.getTicketCode(),
                        "result", result.strip().substring(0, Math.min(800, result.strip().length())),
                        "autoCloseAt", String.valueOf(t.getAutoCloseAt())
                ));
        notifications.createTicketNotice(t.getReporter().getId(),
            t.getId() + ":" + t.getResolutionCycle() + ":RESOLVED",
            title, body,
            "/tickets/" + t.getId(), clock.instant());
    }

    private void notifyTicketClosed(SupportTicket t, String reason) {
        String sourceKey = t.getId() + ":" + t.getResolutionCycle() + ":CLOSED";
        var params = new HashMap<String, Object>();
        params.put("code", t.getTicketCode());
        if (reason != null && !reason.isBlank()) {
            params.put("reason", reason);
        }
        var content = new NotificationContent(
                NotificationI18nKey.format(NotificationI18nKey.TICKET_CLOSED_TITLE, Map.of("code", t.getTicketCode())),
                NotificationI18nKey.format(NotificationI18nKey.TICKET_CLOSED_BODY, params),
                clock.instant());

        UserProfile handler = t.getAssignedHandler();
        if (handler != null) {
            NotificationAudience audience = t.getStation() == null
                    ? NotificationAudience.ADMIN
                    : (handler.getId().equals(t.getStation().getOwner().getId()) ? NotificationAudience.OWNER : NotificationAudience.STAFF);
            notifications.saveIfAbsent(AppNotification.ticketLifecycle(
                    t, handler.getId(), audience, NotificationEventType.TICKET_CLOSED, sourceKey + ":HANDLER", content));
        }

        if (t.getStation() != null) {
            UserProfile owner = t.getStation().getOwner();
            if (owner != null && (handler == null || !owner.getId().equals(handler.getId()))) {
                notifications.saveIfAbsent(AppNotification.ticketLifecycle(
                        t, owner.getId(), NotificationAudience.OWNER, NotificationEventType.TICKET_CLOSED, sourceKey + ":OWNER", content));
            }
        } else if (handler == null) {
            for (UserProfile admin : profiles.findAllByPrimaryRoleAndStatusAndDeletedAtIsNull(
                    PrimaryRole.ADMIN, UserStatus.ACTIVE)) {
                notifications.saveIfAbsent(AppNotification.ticketLifecycle(
                        t, admin.getId(), NotificationAudience.ADMIN, NotificationEventType.TICKET_CLOSED, sourceKey + ":" + admin.getId(), content));
            }
        }
    }

    private void notifyTicketReopened(SupportTicket t, String reason) {
        String sourceKey = t.getId() + ":" + t.getResolutionCycle() + ":REOPENED";
        var params = new HashMap<String, Object>();
        params.put("code", t.getTicketCode());
        if (reason != null && !reason.isBlank()) {
            params.put("reason", reason);
        }
        var content = new NotificationContent(
                NotificationI18nKey.format(NotificationI18nKey.TICKET_REOPENED_TITLE, Map.of("code", t.getTicketCode())),
                NotificationI18nKey.format(NotificationI18nKey.TICKET_REOPENED_BODY, params),
                clock.instant());

        UserProfile handler = t.getAssignedHandler();
        if (handler != null) {
            NotificationAudience audience = t.getStation() == null
                    ? NotificationAudience.ADMIN
                    : (handler.getId().equals(t.getStation().getOwner().getId()) ? NotificationAudience.OWNER : NotificationAudience.STAFF);
            notifications.saveIfAbsent(AppNotification.ticketLifecycle(
                    t, handler.getId(), audience, NotificationEventType.TICKET_REOPENED, sourceKey + ":HANDLER", content));
        }

        if (t.getStation() != null) {
            UserProfile owner = t.getStation().getOwner();
            if (owner != null && (handler == null || !owner.getId().equals(handler.getId()))) {
                notifications.saveIfAbsent(AppNotification.ticketLifecycle(
                        t, owner.getId(), NotificationAudience.OWNER, NotificationEventType.TICKET_REOPENED, sourceKey + ":OWNER", content));
            }
        } else if (handler == null) {
            for (UserProfile admin : profiles.findAllByPrimaryRoleAndStatusAndDeletedAtIsNull(
                    PrimaryRole.ADMIN, UserStatus.ACTIVE)) {
                notifications.saveIfAbsent(AppNotification.ticketLifecycle(
                        t, admin.getId(), NotificationAudience.ADMIN, NotificationEventType.TICKET_REOPENED, sourceKey + ":" + admin.getId(), content));
            }
        }
    }

    @Transactional
    public boolean autoClose(UUID id, Instant now) {
        SupportTicket t = locked(id, null);
        if (t.getStatus() != TicketStatus.RESOLVED || t.getAutoCloseAt() == null || t.getAutoCloseAt().isAfter(now)) return false;
        if (t.getStation() != null && escalations.existsByTicket_IdAndResolvedAtIsNull(t.getId())) return false;
        UUID oldHandler = t.getAssignedHandler() == null ? null : t.getAssignedHandler().getId();
        t.close("AUTO_CLOSED_NO_RESPONSE");
        event(t, null, "SYSTEM", "AUTO_CLOSED_NO_RESPONSE", TicketStatus.RESOLVED, oldHandler, null);
        notifyTicketClosed(t, "Auto-closed: no response from reporter");
        tickets.saveAndFlush(t);
        return true;
    }

    @Transactional
    public void releaseRevokedStaff(UUID stationId, UUID staffId, UUID ownerId) {
        var ids = tickets.findAssignedInProgress(stationId, staffId);
        for (UUID id : ids) {
            SupportTicket t = locked(id, null);
            if (t.getStatus() != TicketStatus.IN_PROGRESS || t.getAssignedHandler() == null ||
                !t.getAssignedHandler().getId().equals(staffId)) continue;
            t.release();
            event(t, ownerId, OWNER, "HANDLER_REVOKED", TicketStatus.IN_PROGRESS, staffId, "Staff assignment revoked");
            tickets.saveAndFlush(t);
        }
    }
}
