package com.thang.chargeops.support.service;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.TicketErrorCode;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.support.dto.request.EscalateTicketRequest;
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
        if (reporter && !owner && !mayReporterEscalate(ticket)) {
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
        return response(escalations.findByTicket_Id(ticketId)
                .orElseThrow(() -> new AppException(TicketErrorCode.NOT_FOUND)));
    }

    @Transactional(readOnly = true)
    public Page<TicketEscalationResponse> adminQueue(int page, int size) {
        currentProfile.requireProfile();
        if (!access.hasRole("ADMIN")) throw new AppException(TicketErrorCode.ACCESS_DENIED);
        return escalations.findAll(PageRequest.of(page - 1, size,
                Sort.by(Sort.Order.desc("requestedAt"), Sort.Order.desc("id")))).map(this::response);
    }

    private boolean mayReporterEscalate(SupportTicket ticket) {
        var latestFinding = findings.findFirstByTicket_IdOrderByRecordedAtDescIdDesc(ticket.getId());
        if (latestFinding.isPresent() && latestFinding.get().getConclusion() ==
                TicketFindingConclusion.NOT_STATION_FAILURE) return true;
        if (events.findFirstByTicketIdAndEventTypeOrderByCreatedAtDescIdDesc(
                ticket.getId(), "REPORTER_CONTINUED").isPresent()) return true;
        var thread = messages.findByTicketIdOrderByCreatedAtAscIdAsc(ticket.getId());
        Instant lastReporter = null;
        Instant lastStation = null;
        for (var message : thread) {
            if (message.getAuthorKind() == TicketActorKind.REPORTER) lastReporter = message.getCreatedAt();
            if (message.getAuthorKind() == TicketActorKind.OWNER
                    || message.getAuthorKind() == TicketActorKind.STAFF) lastStation = message.getCreatedAt();
        }
        return lastReporter != null && (lastStation == null || lastStation.isBefore(lastReporter))
                && !clock.instant().isBefore(lastReporter.plus(OWNER_RESPONSE_WINDOW));
    }

    private TicketEscalationResponse response(TicketEscalation escalation) {
        return new TicketEscalationResponse(escalation.getTicket().getId(),
                escalation.getRequestedBy().getId(), escalation.getRequestedAt(), escalation.getReason());
    }
}
