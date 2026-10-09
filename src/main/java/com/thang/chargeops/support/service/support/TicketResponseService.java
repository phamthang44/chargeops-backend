package com.thang.chargeops.support.service.support;

import com.thang.chargeops.common.entity.BaseEntity;
import com.thang.chargeops.refund.entity.Refund;
import com.thang.chargeops.refund.repository.RefundRepository;
import com.thang.chargeops.support.dto.response.TicketFindingResponse;
import com.thang.chargeops.support.dto.response.TicketMessageResponse;
import com.thang.chargeops.support.dto.response.TicketResponse;
import com.thang.chargeops.support.dto.response.TicketDetailResponse;
import com.thang.chargeops.support.service.TicketEscalationService;
import com.thang.chargeops.support.service.AdminFinanceAccessAudit;
import com.thang.chargeops.support.service.TicketAccessPolicy;
import com.thang.chargeops.booking.service.ServiceFailureDecisionReader;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.support.entity.SupportTicket;
import com.thang.chargeops.support.entity.TicketFinding;
import com.thang.chargeops.support.entity.TicketMessage;
import com.thang.chargeops.support.entity.TicketEvent;
import com.thang.chargeops.support.repository.TicketFindingRepository;
import com.thang.chargeops.support.repository.TicketMessageRepository;
import com.thang.chargeops.support.repository.TicketEventRepository;
import com.thang.chargeops.support.repository.SupportTicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TicketResponseService {

    private final TicketMessageRepository messageRepository;
    private final TicketFindingRepository findingRepository;
    private final RefundRepository refundRepository;
    private final SupportTicketRepository ticketRepository;
    private final TicketEventRepository eventRepository;
    private final TicketResponseMapper mapper;
    private final TicketEscalationService escalationService;
    private final ServiceFailureDecisionReader decisionReader;
    private final CurrentProfileProvider currentProfile;
    private final TicketAccessPolicy access;
    private final AdminFinanceAccessAudit accessAudit;

    public TicketResponse toResponse(SupportTicket ticket) {
        if (ticket == null) {
            return null;
        }
        return toResponses(List.of(ticket)).getFirst();
    }

    public TicketDetailResponse toDetail(SupportTicket ticket) {
        if (ticket == null) return null;
        TicketResponse snapshot = toResponse(ticket);
        TicketEscalationService.State state = escalationService.describe(ticket);
        UUID ownerId = ticket.getStation() == null ? null : ticket.getStation().getOwner().getId();
        var detail = TicketDetailResponse.from(snapshot, state.escalation(), state.availability(), ownerId);
        if (ticket.getBooking() == null) return detail;
        var actor = currentProfile.requireProfile();
        boolean owner = access.isOwner(ticket, actor.getId());
        boolean reporter = access.hasRole("DRIVER") && ticket.getReporter().getId().equals(actor.getId());
        boolean admin = access.hasRole("ADMIN") && access.canRead(ticket, actor);
        if (!owner && !reporter && !admin) return detail;
        var financial = decisionReader.forTicket(ticket.getBooking().getId(), ticket.getId(), owner);
        if (admin) accessAudit.appendAccessEvent(ticket, actor, null, "TICKET_FINANCIAL_RESOLUTION");
        return detail.withFinancialResolution(financial);
    }

    public List<TicketResponse> toResponses(List<SupportTicket> tickets) {
        if (tickets == null || tickets.isEmpty()) {
            return Collections.emptyList();
        }

        List<UUID> ticketIds = tickets.stream().map(SupportTicket::getId).toList();
        Map<UUID, SupportTicket> contextById = tickets.size() > 1
                ? ticketRepository.findAllWithContext(ticketIds).stream()
                    .collect(Collectors.toMap(SupportTicket::getId, ticket -> ticket))
                : Collections.emptyMap();
        List<SupportTicket> expanded = tickets.stream()
                .map(ticket -> contextById.getOrDefault(ticket.getId(), ticket)).toList();
        List<UUID> bookingIds = expanded.stream()
                .map(SupportTicket::getBooking)
                .filter(Objects::nonNull)
                .map(BaseEntity::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<UUID, List<TicketMessageResponse>> messagesByTicket = messageRepository
                .findByTicket_IdInOrderByCreatedAtAscIdAsc(ticketIds).stream()
                .collect(Collectors.groupingBy(
                        msg -> msg.getTicket().getId(),
                        Collectors.mapping(this::toMessageResponse, Collectors.toList())
                ));

        Map<UUID, List<TicketFindingResponse>> findingsByTicket = findingRepository
                .findByTicket_IdInOrderByRecordedAtAscIdAsc(ticketIds).stream()
                .collect(Collectors.groupingBy(
                        finding -> finding.getTicket().getId(),
                        Collectors.mapping(this::toFindingResponse, Collectors.toList())
                ));

        // Staff-only console records operational facts; financial identifiers belong to
        // Owner/Admin or to a Driver viewing their own case through the existing flow.
        boolean staffOnly = access.hasRole("STAFF")
                && !access.hasRole("OWNER") && !access.hasRole("ADMIN");
        Map<UUID, List<UUID>> refundIdsByBooking;
        if (bookingIds.isEmpty() || staffOnly) {
            refundIdsByBooking = Collections.emptyMap();
        } else {
            refundIdsByBooking = refundRepository
                    .findByBookingIdInOrderByCreatedAtAscIdAsc(bookingIds).stream()
                    .collect(Collectors.groupingBy(
                            refund -> refund.getBooking().getId(),
                            Collectors.mapping(Refund::getId, Collectors.toList())
                    ));
        }

        Map<UUID, String> resolutionReasons = new HashMap<>();
        Map<UUID, Instant> closedTimes = new HashMap<>();
        for (TicketEvent event : eventRepository.findWorkflowFacts(ticketIds,
                List.of("RESOLVED", "REPORTER_CONFIRMED", "AUTO_CLOSED_NO_RESPONSE", "CLOSE_SUPPORT_CASE"))) {
            if ("RESOLVED".equals(event.getEventType())) {
                resolutionReasons.putIfAbsent(event.getTicketId(), event.getReason());
            } else {
                closedTimes.putIfAbsent(event.getTicketId(), event.getCreatedAt());
            }
        }

        return expanded.stream().map(ticket -> {
            UUID ticketId = ticket.getId();
            UUID bookingId = ticket.getBooking() == null ? null : ticket.getBooking().getId();
            List<TicketMessageResponse> messages = messagesByTicket.getOrDefault(ticketId, Collections.emptyList());
            List<TicketFindingResponse> findings = findingsByTicket.getOrDefault(ticketId, Collections.emptyList());
            List<UUID> refundIds = bookingId == null ? Collections.emptyList()
                    : refundIdsByBooking.getOrDefault(bookingId, Collections.emptyList());
            if (access.hasRole("ADMIN") && !refundIds.isEmpty()) {
                accessAudit.appendAccessEvent(ticket, currentProfile.requireProfile(), null, "TICKET_REFUND_REFERENCES");
            }
            return mapper.map(ticket, messages, findings, refundIds,
                    closedTimes.get(ticketId), resolutionReasons.get(ticketId));
        }).toList();
    }

    public TicketMessageResponse toMessageResponse(TicketMessage message) {
        return mapper.message(message);
    }

    public TicketFindingResponse toFindingResponse(TicketFinding finding) {
        return mapper.finding(finding);
    }
}
