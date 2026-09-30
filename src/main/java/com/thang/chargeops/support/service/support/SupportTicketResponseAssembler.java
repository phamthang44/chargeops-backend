package com.thang.chargeops.support.service.support;

import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.refund.entity.Refund;
import com.thang.chargeops.refund.repository.RefundRepository;
import com.thang.chargeops.support.dto.response.TicketFindingResponse;
import com.thang.chargeops.support.dto.response.TicketMessageResponse;
import com.thang.chargeops.support.dto.response.TicketResponse;
import com.thang.chargeops.support.entity.SupportTicket;
import com.thang.chargeops.support.entity.TicketFinding;
import com.thang.chargeops.support.entity.TicketMessage;
import com.thang.chargeops.support.repository.TicketFindingRepository;
import com.thang.chargeops.support.repository.TicketMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class SupportTicketResponseAssembler {

    private final TicketMessageRepository messageRepository;
    private final TicketFindingRepository findingRepository;
    private final RefundRepository refundRepository;

    public TicketResponse toResponse(SupportTicket ticket) {
        if (ticket == null) {
            return null;
        }
        return toResponses(List.of(ticket)).getFirst();
    }

    public List<TicketResponse> toResponses(List<SupportTicket> tickets) {
        if (tickets == null || tickets.isEmpty()) {
            return Collections.emptyList();
        }

        List<UUID> ticketIds = tickets.stream().map(SupportTicket::getId).toList();
        List<UUID> bookingIds = tickets.stream()
                .map(SupportTicket::getBooking)
                .filter(Objects::nonNull)
                .map(b -> b.getId())
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

        Map<UUID, List<UUID>> refundIdsByBooking;
        if (bookingIds.isEmpty()) {
            refundIdsByBooking = Collections.emptyMap();
        } else {
            refundIdsByBooking = refundRepository
                    .findByBookingIdInOrderByCreatedAtAscIdAsc(bookingIds).stream()
                    .collect(Collectors.groupingBy(
                            refund -> refund.getBooking().getId(),
                            Collectors.mapping(Refund::getId, Collectors.toList())
                    ));
        }

        return tickets.stream().map(ticket -> {
            UUID ticketId = ticket.getId();
            UUID bookingId = ticket.getBooking() == null ? null : ticket.getBooking().getId();
            UUID stationId = ticket.getStation() == null ? null : ticket.getStation().getId();
            UUID reporterId = ticket.getReporter() == null ? null : ticket.getReporter().getId();
            UUID handlerId = ticket.getAssignedHandler() == null ? null : ticket.getAssignedHandler().getId();

            List<TicketMessageResponse> messages = messagesByTicket.getOrDefault(ticketId, Collections.emptyList());
            List<TicketFindingResponse> findings = findingsByTicket.getOrDefault(ticketId, Collections.emptyList());
            List<UUID> refundIds = bookingId == null
                    ? Collections.emptyList()
                    : refundIdsByBooking.getOrDefault(bookingId, Collections.emptyList());

            return new TicketResponse(
                    ticket.getId(),
                    ticket.getTicketCode(),
                    ticket.getCategory(),
                    ticket.getPriority(),
                    ticket.getSubject(),
                    ticket.getStatus(),
                    ticket.getVersion(),
                    bookingId,
                    stationId,
                    reporterId,
                    handlerId,
                    ticket.getCreatedAt(),
                    messages,
                    findings,
                    refundIds
            );
        }).toList();
    }

    public TicketMessageResponse toMessageResponse(TicketMessage message) {
        if (message == null) {
            return null;
        }
        UserProfile author = message.getAuthor();
        String displayName = null;
        if (author != null) {
            displayName = author.getDisplayName();
            if (displayName == null || displayName.isBlank()) {
                displayName = author.getEmail();
            }
        }
        return new TicketMessageResponse(
                message.getId(),
                displayName,
                message.getAuthorKind(),
                message.getBody(),
                message.getCreatedAt()
        );
    }

    public TicketFindingResponse toFindingResponse(TicketFinding finding) {
        if (finding == null) {
            return null;
        }
        UUID recordedById = finding.getRecordedBy() == null ? null : finding.getRecordedBy().getId();
        return new TicketFindingResponse(
                finding.getId(),
                finding.getConclusion(),
                finding.getAffectedAt(),
                finding.getReason(),
                finding.getRecordedAt(),
                recordedById
        );
    }
}
