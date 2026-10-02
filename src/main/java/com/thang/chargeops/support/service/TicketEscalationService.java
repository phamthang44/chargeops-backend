package com.thang.chargeops.support.service;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.TicketErrorCode;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.support.dto.request.EscalateTicketRequest;
import com.thang.chargeops.support.dto.response.TicketEscalationAvailabilityResponse;
import com.thang.chargeops.support.dto.response.TicketEscalationResponse;
import com.thang.chargeops.support.entity.SupportTicket;
import com.thang.chargeops.support.entity.TicketEscalation;
import com.thang.chargeops.support.model.TicketActorKind;
import com.thang.chargeops.support.model.TicketFindingConclusion;
import com.thang.chargeops.support.model.TicketStatus;
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

    @Transactional
    public TicketEscalationResponse request(UUID ticketId, EscalateTicketRequest request) {
        var actor = currentProfile.requireProfile();
        SupportTicket ticket = tickets.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new AppException(TicketErrorCode.NOT_FOUND));
        if (ticket.getStation() == null || ticket.getStatus() == TicketStatus.CLOSED) {
            throw new AppException(TicketErrorCode.INVALID_SCOPE);
        }
        boolean owner = access.isOwner(ticket, actor.getId());
        boolean reporter = ticket.getReporter().getId().equals(actor.getId());
        if (!owner && !reporter) throw new AppException(TicketErrorCode.ACCESS_DENIED);
        var existing = escalations.findByTicket_Id(ticketId);
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
        return escalations.findByTicket_Id(ticketId).map(this::response).orElse(null);
    }

    @Transactional(readOnly = true)
    public State describe(SupportTicket ticket) {
        var actor = currentProfile.requireProfile();
        if (!access.canRead(ticket, actor)) throw new AppException(TicketErrorCode.ACCESS_DENIED);
        var existing = escalations.findByTicket_Id(ticket.getId());
        if (existing.isPresent()) {
            return new State(response(existing.get()),
                    new TicketEscalationAvailabilityResponse(false, null,
                            TicketEscalationAvailabilityResponse.Reason.ALREADY_ESCALATED));
        }
        return new State(null, availability(ticket, actor));
    }

    @Transactional(readOnly = true)
    public Page<TicketEscalationResponse> adminQueue(int page, int size) {
        currentProfile.requireProfile();
        if (!access.hasRole("ADMIN")) throw new AppException(TicketErrorCode.ACCESS_DENIED);
        return escalations.findAll(PageRequest.of(page - 1, size,
                Sort.by(Sort.Order.desc("requestedAt"), Sort.Order.desc("id")))).map(this::response);
    }

    private TicketEscalationAvailabilityResponse availability(SupportTicket ticket,
                                                               com.thang.chargeops.profile.entity.UserProfile actor) {
        var reason = TicketEscalationAvailabilityResponse.Reason.NOT_REQUESTER;
        if (ticket.getStation() == null) return unavailable(TicketEscalationAvailabilityResponse.Reason.PLATFORM_TICKET);
        if (ticket.getStatus() == TicketStatus.CLOSED) return unavailable(TicketEscalationAvailabilityResponse.Reason.TICKET_CLOSED);
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
                escalation.getRequestedBy().getId(), escalation.getRequestedAt(), escalation.getReason());
    }
}
