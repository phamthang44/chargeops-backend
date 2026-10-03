package com.thang.chargeops.support.service;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.exception.errorcode.TicketErrorCode;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.support.dto.request.EscalateTicketRequest;
import com.thang.chargeops.support.dto.request.ReviewTicketEscalationRequest;
import com.thang.chargeops.support.dto.response.TicketEscalationAvailabilityResponse;
import com.thang.chargeops.support.dto.response.TicketEscalationDetailResponse;
import com.thang.chargeops.support.dto.response.TicketEscalationResponse;
import com.thang.chargeops.support.dto.response.TicketEscalationsSummaryResponse;
import com.thang.chargeops.support.entity.SupportTicket;
import com.thang.chargeops.support.entity.TicketEscalation;
import com.thang.chargeops.support.model.TicketActorKind;
import com.thang.chargeops.support.model.TicketFindingConclusion;
import com.thang.chargeops.support.model.TicketStatus;
import com.thang.chargeops.support.model.TicketEscalationResolutionType;
import com.thang.chargeops.support.entity.TicketEvent;
import com.thang.chargeops.support.repository.SupportTicketRepository;
import com.thang.chargeops.support.repository.TicketEscalationRepository;
import com.thang.chargeops.support.repository.TicketEventRepository;
import com.thang.chargeops.support.repository.TicketFindingRepository;
import com.thang.chargeops.support.repository.TicketMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TicketEscalationService {
    private static final Duration OWNER_RESPONSE_WINDOW = Duration.ofHours(24);

    public record State(TicketEscalationResponse escalation,
                        TicketEscalationAvailabilityResponse availability) {}

    private final CurrentProfileProvider currentProfile;
    private final TicketAccessPolicy access;
    private final SupportTicketRepository tickets;
    private final TicketEscalationRepository escalations;
    private final TicketFindingRepository findings;
    private final TicketEventRepository events;
    private final TicketMessageRepository messages;
    private final Clock clock;

    @Transactional(readOnly = true)
    public TicketEscalationsSummaryResponse adminSummary() {
        currentProfile.requireProfile();
        if (!access.hasRole("ADMIN")) throw new AppException(TicketErrorCode.ACCESS_DENIED);
        return new TicketEscalationsSummaryResponse(escalations.count(),
                escalations.countPendingArbiter(), escalations.countUnresponsiveAtRequest(),
                escalations.countDisputedFindingsAtRequest());
    }

    @Transactional
    public TicketEscalationResponse request(UUID ticketId, EscalateTicketRequest request) {
        var actor = currentProfile.requireProfile();
        SupportTicket ticket = tickets.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new AppException(TicketErrorCode.NOT_FOUND));
        if (ticket.getStation() == null) {
            throw new AppException(TicketErrorCode.INVALID_SCOPE);
        }
        if (ticket.getStatus() != TicketStatus.OPEN && ticket.getStatus() != TicketStatus.IN_PROGRESS)
            throw new AppException(TicketErrorCode.STATE_CONFLICT);
        boolean owner = access.isOwner(ticket, actor.getId());
        boolean reporter = ticket.getReporter().getId().equals(actor.getId());
        if (!owner && !reporter) throw new AppException(TicketErrorCode.ACCESS_DENIED);
        var existing = escalations.findByTicket_IdAndResolvedAtIsNull(ticketId);
        if (existing.isPresent()) return response(existing.get());
        if (reporter && !owner && !availability(ticket, actor).canRequest()) {
            throw new AppException(TicketErrorCode.STATE_CONFLICT);
        }
        return response(escalations.saveAndFlush(
                TicketEscalation.request(ticket, actor, clock.instant(), request.reason())));
    }

    @Transactional(readOnly = true)
    public TicketEscalationResponse get(UUID ticketId) {
        var actor = currentProfile.requireProfile();
        var ticket = tickets.findById(ticketId).orElseThrow(() -> new AppException(TicketErrorCode.NOT_FOUND));
        if (!access.canRead(ticket, actor)) throw new AppException(TicketErrorCode.ACCESS_DENIED);
        return escalations.findByTicket_IdAndResolvedAtIsNull(ticketId)
                .or(() -> escalations.findFirstByTicket_IdOrderByRequestedAtDescIdDesc(ticketId))
                .map(this::response).orElse(null);
    }

    @Transactional(readOnly = true)
    public TicketEscalationDetailResponse getDetail(UUID ticketId) {
        var actor = currentProfile.requireProfile();
        var ticket = tickets.findById(ticketId).orElseThrow(() -> new AppException(TicketErrorCode.NOT_FOUND));
        if (!access.canRead(ticket, actor)) throw new AppException(TicketErrorCode.ACCESS_DENIED);
        UUID ownerId = ticket.getStation() != null && ticket.getStation().getOwner() != null
                ? ticket.getStation().getOwner().getId() : null;
        UUID reporterId = ticket.getReporter() != null ? ticket.getReporter().getId() : null;
        return escalations.findByTicket_IdAndResolvedAtIsNull(ticketId)
                .or(() -> escalations.findFirstByTicket_IdOrderByRequestedAtDescIdDesc(ticketId))
                .map(e -> TicketEscalationDetailResponse.from(response(e), ownerId, reporterId))
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public State describe(SupportTicket ticket) {
        var actor = currentProfile.requireProfile();
        if (!access.canRead(ticket, actor)) throw new AppException(TicketErrorCode.ACCESS_DENIED);
        var existing = escalations.findByTicket_IdAndResolvedAtIsNull(ticket.getId());
        if (existing.isPresent()) {
            return new State(response(existing.get()),
                    new TicketEscalationAvailabilityResponse(false, null,
                            TicketEscalationAvailabilityResponse.Reason.ALREADY_ESCALATED));
        }
        return new State(escalations.findFirstByTicket_IdOrderByRequestedAtDescIdDesc(ticket.getId())
                .map(this::response).orElse(null), availability(ticket, actor));
    }

    @Transactional(readOnly = true)
    public Page<TicketEscalationResponse> adminQueue(int page, int size) {
        currentProfile.requireProfile();
        if (!access.hasRole("ADMIN")) throw new AppException(TicketErrorCode.ACCESS_DENIED);
        return escalations.findByResolvedAtIsNull(PageRequest.of(page - 1, size,
                Sort.by(Sort.Order.desc("requestedAt"), Sort.Order.desc("id")))).map(this::response);
    }

    @Transactional
    public TicketEscalationResponse review(UUID ticketId, ReviewTicketEscalationRequest request) {
        var admin = currentProfile.requireProfile();
        if (!access.hasRole("ADMIN") || admin.getStatus() != UserStatus.ACTIVE)
            throw new AppException(TicketErrorCode.ACCESS_DENIED);
        SupportTicket ticket = tickets.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new AppException(TicketErrorCode.NOT_FOUND));
        if (ticket.getStation() == null) throw new AppException(TicketErrorCode.INVALID_SCOPE);
        if (!request.expectedVersion().equals(ticket.getVersion())) throw new AppException(TicketErrorCode.VERSION_CONFLICT);
        if (ticket.getStatus() != TicketStatus.OPEN && ticket.getStatus() != TicketStatus.IN_PROGRESS)
            throw new AppException(TicketErrorCode.STATE_CONFLICT);
        TicketEscalation escalation = escalations.findByTicket_IdAndResolvedAtIsNull(ticketId)
                .orElseThrow(() -> new AppException(TicketErrorCode.STATE_CONFLICT));
        if (request.action() == TicketEscalationResolutionType.RETURN_TO_STATION && request.closureReason() != null
                || request.action() == TicketEscalationResolutionType.CLOSE_SUPPORT_CASE && request.closureReason() == null)
            throw new AppException(TicketErrorCode.STATE_CONFLICT);
        TicketStatus before = ticket.getStatus();
        UUID oldHandler = ticket.getAssignedHandler() == null ? null : ticket.getAssignedHandler().getId();
        Instant now = clock.instant();
        escalation.review(admin, now, request.action(), request.closureReason(), request.note());
        if (request.action() == TicketEscalationResolutionType.RETURN_TO_STATION) ticket.returnFromEscalation();
        else ticket.closeEscalatedSupportCase();
        ticket.setUpdatedAt(now);
        events.saveAndFlush(TicketEvent.create(ticket,
                new TicketEvent.EventActor(admin.getId(), "ADMIN"), request.action().name(),
                before, oldHandler, request.note().strip(), now));
        tickets.saveAndFlush(ticket);
        escalations.saveAndFlush(escalation);
        return response(escalation);
    }

    private TicketEscalationAvailabilityResponse availability(SupportTicket ticket,
                                                               com.thang.chargeops.profile.entity.UserProfile actor) {
        var reason = TicketEscalationAvailabilityResponse.Reason.NOT_REQUESTER;
        if (ticket.getStation() == null) return unavailable(TicketEscalationAvailabilityResponse.Reason.PLATFORM_TICKET);
        if (ticket.getStatus() == TicketStatus.CLOSED) return unavailable(TicketEscalationAvailabilityResponse.Reason.TICKET_CLOSED);
        if (ticket.getStatus() == TicketStatus.RESOLVED) return unavailable(TicketEscalationAvailabilityResponse.Reason.TICKET_AWAITING_REPORTER);
        if (access.isOwner(ticket, actor.getId())) {
            return available(TicketEscalationAvailabilityResponse.Reason.OWNER_CAN_REQUEST);
        }
        if (!ticket.getReporter().getId().equals(actor.getId()) || !access.hasRole("DRIVER")) {
            return unavailable(reason);
        }
        var latestFinding = findings.findFirstByTicket_IdOrderByRecordedAtDescIdDesc(ticket.getId());
        if (latestFinding.isPresent() && latestFinding.get().getConclusion() ==
                TicketFindingConclusion.NOT_STATION_FAILURE) {
            return available(TicketEscalationAvailabilityResponse.Reason.STATION_DENIED);
        }
        if (events.findFirstByTicketIdAndEventTypeOrderByCreatedAtDescIdDesc(
                ticket.getId(), "REPORTER_CONTINUED").isPresent()) {
            return available(TicketEscalationAvailabilityResponse.Reason.REPORTER_CONTINUED);
        }
        var thread = messages.findByTicketIdOrderByCreatedAtAscIdAsc(ticket.getId());
        Instant lastReporter = null;
        Instant lastStation = null;
        for (var message : thread) {
            if (message.getAuthorKind() == TicketActorKind.REPORTER) lastReporter = message.getCreatedAt();
            if (message.getAuthorKind() == TicketActorKind.OWNER
                    || message.getAuthorKind() == TicketActorKind.STAFF) lastStation = message.getCreatedAt();
        }
        if (lastReporter == null) return unavailable(TicketEscalationAvailabilityResponse.Reason.NO_REPORTER_MESSAGE);
        if (lastStation != null && !lastStation.isBefore(lastReporter)) {
            return unavailable(TicketEscalationAvailabilityResponse.Reason.STATION_RESPONDED);
        }
        Instant availableAt = lastReporter.plus(OWNER_RESPONSE_WINDOW);
        return clock.instant().isBefore(availableAt)
                ? new TicketEscalationAvailabilityResponse(false, availableAt,
                        TicketEscalationAvailabilityResponse.Reason.WAITING_FOR_STATION)
                : new TicketEscalationAvailabilityResponse(true, availableAt,
                        TicketEscalationAvailabilityResponse.Reason.STATION_UNRESPONSIVE);
    }

    private TicketEscalationAvailabilityResponse available(TicketEscalationAvailabilityResponse.Reason reason) {
        return new TicketEscalationAvailabilityResponse(true, null, reason);
    }

    private TicketEscalationAvailabilityResponse unavailable(TicketEscalationAvailabilityResponse.Reason reason) {
        return new TicketEscalationAvailabilityResponse(false, null, reason);
    }

    private TicketEscalationResponse response(TicketEscalation escalation) {
        return new TicketEscalationResponse(escalation.getTicket().getId(),
                escalation.getRequestedBy().getId(), escalation.getRequestedAt(), escalation.getReason(),
                escalation.getResolvedAt(), escalation.getResolvedBy() == null ? null : escalation.getResolvedBy().getId(),
                escalation.getResolutionType(), escalation.getResolutionNote(), escalation.getClosureReason());
    }
}
