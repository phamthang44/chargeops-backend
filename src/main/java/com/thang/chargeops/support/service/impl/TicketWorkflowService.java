package com.thang.chargeops.support.service.impl;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.TicketErrorCode;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.infra.identity.IdentityRoleService;
import com.thang.chargeops.common.enums.Role;
import com.thang.chargeops.common.enums.UserStatus;
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
import com.thang.chargeops.support.entity.TicketEvent;
import com.thang.chargeops.support.service.TicketAccessPolicy;
import com.thang.chargeops.support.service.support.TicketResponseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
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
        if (t.getStatus() != TicketStatus.OPEN || t.getAssignedHandler() != null) throw new AppException(TicketErrorCode.STATE_CONFLICT);
        TicketStatus before = t.getStatus();
        t.assign(actor);
        event(t, actor.getId(), kind(t, actor.getId()), "CLAIMED", before, null, null);
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
        UserProfile handler = assignmentHandler(t, request.handlerId());
        UUID oldHandler = t.getAssignedHandler() == null ? null : t.getAssignedHandler().getId();
        if (handler.getId().equals(oldHandler)) throw new AppException(TicketErrorCode.STATE_CONFLICT);
        TicketStatus before = t.getStatus();
        t.assign(handler);
        event(t, actor.getId(), kind(t, actor.getId()), before == TicketStatus.OPEN ? "ASSIGNED" : "REASSIGNED", before, oldHandler, request.reason());
        return saved(t);
    }

    private void requireAssigner(SupportTicket ticket, UUID actorId) {
        if (ticket.getStation() == null ? !access.hasRole(ADMIN) : !owner(ticket, actorId)) {
            throw new AppException(TicketErrorCode.ACCESS_DENIED);
        }
    }

    private UserProfile assignmentHandler(SupportTicket t, UUID handlerId) {
        if (t.getStatus() != TicketStatus.OPEN && t.getStatus() != TicketStatus.IN_PROGRESS) throw new AppException(TicketErrorCode.STATE_CONFLICT);
        UserProfile handler = profiles.findById(handlerId).orElseThrow(() -> new AppException(TicketErrorCode.ASSIGNMENT_INVALID));
        if (handler.getStatus() != UserStatus.ACTIVE) throw new AppException(TicketErrorCode.ASSIGNMENT_INVALID);
        if (t.getStation() != null && !handler.getId().equals(t.getStation().getOwner().getId()) &&
            !staffAssignments.existsByStation_IdAndStaff_IdAndStatus(t.getStation().getId(), handler.getId(), StaffAssignmentStatus.ACTIVE))
            throw new AppException(TicketErrorCode.ASSIGNMENT_INVALID);
        if (t.getStation() == null && !identityRoles.getRoles(handler.getKeycloakId()).contains(Role.ADMIN))
            throw new AppException(TicketErrorCode.ASSIGNMENT_INVALID);
        if (t.getStation() != null && t.getStatus() == TicketStatus.OPEN &&
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
        t.resolve(clock.instant());
        event(t, actorId, kind(t, actorId), "RESOLVED", TicketStatus.IN_PROGRESS, oldHandler, reason);
        notice(t, reason);
    }

    private void confirmByReporter(SupportTicket t, UUID actorId, UUID oldHandler, String reason) {
        if (!t.getReporter().getId().equals(actorId)) throw new AppException(TicketErrorCode.ACCESS_DENIED);
        t.close("REPORTER_CONFIRMED");
        event(t, actorId, REPORTER, "REPORTER_CONFIRMED", TicketStatus.RESOLVED, oldHandler, reason);
    }

    private void continueAfterResolution(SupportTicket t, UUID actorId, UUID oldHandler, String reason) {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Continuation reason is required");
        if (t.getReporter().getId().equals(actorId)) {
            continueTicket(t, actorId, reason);
        } else if (actorId.equals(oldHandler) && eligible(t, actorId)) {
            t.continueWork(t.getAssignedHandler());
            event(t, actorId, kind(t, actorId), "HANDLER_CONTINUED", TicketStatus.RESOLVED, oldHandler, reason);
        } else {
            throw new AppException(TicketErrorCode.ACCESS_DENIED);
        }
    }

    @Transactional
    public void continueFromReply(UUID id, UserProfile reporter, String reason) {
        SupportTicket t = locked(id, null);
        if (t.getStatus() != TicketStatus.RESOLVED || !t.getReporter().getId().equals(reporter.getId()))
            throw new AppException(TicketErrorCode.STATE_CONFLICT);
        continueTicket(t, reporter.getId(), reason);
        tickets.saveAndFlush(t);
    }

    private void continueTicket(SupportTicket t, UUID reporter, String reason) {
        UUID oldHandler = t.getAssignedHandler() == null ? null : t.getAssignedHandler().getId();
        UserProfile handler = oldHandler != null && eligibleHandler(t) ? t.getAssignedHandler() : null;
        t.continueWork(handler);
        event(t, reporter, REPORTER, "REPORTER_CONTINUED", TicketStatus.RESOLVED, oldHandler, reason);
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
        String body = "Ticket " + t.getTicketCode() + " đã được đánh dấu giải quyết: " + result.strip().substring(0, Math.min(800, result.strip().length())) +
            ". Vui lòng xác nhận hoặc báo vấn đề vẫn còn trước " + t.getAutoCloseAt() +
            ". Nếu không phản hồi trong 10 ngày, ticket tự đóng theo chính sách; đây không phải xác nhận lỗi trạm hay hoàn tiền.";
        notifications.createTicketNotice(t.getReporter().getId(),
            t.getId() + ":" + t.getResolutionCycle() + ":RESOLVED",
            "Ticket " + t.getTicketCode() + " đã giải quyết", body,
            "/tickets/" + t.getId(), clock.instant());
    }

    @Transactional
    public boolean autoClose(UUID id, Instant now) {
        SupportTicket t = locked(id, null);
        if (t.getStatus() != TicketStatus.RESOLVED || t.getAutoCloseAt() == null || t.getAutoCloseAt().isAfter(now)) return false;
        UUID oldHandler = t.getAssignedHandler() == null ? null : t.getAssignedHandler().getId();
        t.close("AUTO_CLOSED_NO_RESPONSE");
        event(t, null, "SYSTEM", "AUTO_CLOSED_NO_RESPONSE", TicketStatus.RESOLVED, oldHandler, null);
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
