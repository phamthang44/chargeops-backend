package com.thang.chargeops.support.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.thang.chargeops.support.model.TicketCategory;
import com.thang.chargeops.support.model.TicketPriority;
import com.thang.chargeops.support.model.TicketStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TicketDetailResponse(
        Overview overview,
        Station station,
        Booking booking,
        Participants participants,
        Conversation conversation,
        Resolution resolution,
        boolean isEscalated,
        @JsonInclude(JsonInclude.Include.ALWAYS) TicketEscalationResponse escalation,
        TicketEscalationAvailabilityResponse escalationAvailability
) {
    public static TicketDetailResponse from(TicketResponse ticket,
                                            TicketEscalationResponse escalation,
                                            TicketEscalationAvailabilityResponse availability) {
        return new TicketDetailResponse(
                new Overview(ticket.ticketId(), ticket.ticketCode(), ticket.category(), ticket.priority(),
                        ticket.subject(), ticket.status(), ticket.version(), ticket.description(),
                        ticket.createdAt(), ticket.updatedAt()),
                new Station(ticket.stationId(), ticket.stationName(), ticket.stationCode(),
                        ticket.stationAddress(), ticket.stationOperationalStatus()),
                new Booking(ticket.bookingId(), ticket.bookingCode(), ticket.bookingStartAt(), ticket.bookingEndAt()),
                new Participants(ticket.reporterId(), ticket.reporterName(), ticket.reporterPhone(),
                        ticket.assignedHandlerId(), ticket.assignedHandlerName(), ticket.assignedHandlerKind()),
                new Conversation(ticket.messages(), ticket.lastMessagePreview(), ticket.messageCount()),
                new Resolution(ticket.findings(), ticket.refundIds(), ticket.resolvedAt(), ticket.autoCloseAt(),
                        ticket.closeReason(), ticket.resolutionCycle(), ticket.closedAt(), ticket.resolutionReason()),
                escalation != null, escalation, availability);
    }

    public record Overview(UUID ticketId, String ticketCode, TicketCategory category, TicketPriority priority,
                           String subject, TicketStatus status, Long version, String description,
                           Instant createdAt, Instant updatedAt) {}

    public record Station(UUID stationId, String name, String code, String address, String operationalStatus) {}

    public record Booking(UUID bookingId, String code, Instant startAt, Instant endAt) {}

    public record Participants(UUID reporterId, String reporterName, String reporterPhone,
                               UUID assignedHandlerId, String assignedHandlerName, String assignedHandlerKind) {}

    public record Conversation(List<TicketMessageResponse> messages, String lastMessagePreview, int messageCount) {}

    public record Resolution(List<TicketFindingResponse> findings, List<UUID> refundIds,
                             Instant resolvedAt, Instant autoCloseAt, String closeReason,
                             int resolutionCycle, Instant closedAt, String resolutionReason) {}
}
