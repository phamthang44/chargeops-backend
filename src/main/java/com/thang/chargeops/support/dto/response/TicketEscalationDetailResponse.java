package com.thang.chargeops.support.dto.response;

import java.time.Instant;
import java.util.UUID;

/** Request facts plus the requester's business role in this ticket. */
public record TicketEscalationDetailResponse(
        UUID ticketId,
        UUID requestedBy,
        Instant requestedAt,
        String reason,
        String requestedByRole,
        Instant resolvedAt,
        UUID resolvedBy,
        String resolutionType,
        String resolutionNote,
        String closureReason
) {
    public TicketEscalationDetailResponse(UUID ticketId, UUID requestedBy, Instant requestedAt,
                                          String reason, String requestedByRole) {
        this(ticketId, requestedBy, requestedAt, reason, requestedByRole, null, null, null, null, null);
    }
    public static TicketEscalationDetailResponse from(TicketEscalationResponse escalation,
                                                       UUID ownerId, UUID reporterId) {
        if (escalation == null) return null;
        String role = escalation.requestedBy().equals(ownerId) ? "owner"
                : escalation.requestedBy().equals(reporterId) ? "driver" : null;
        return new TicketEscalationDetailResponse(escalation.ticketId(), escalation.requestedBy(),
                escalation.requestedAt(), escalation.reason(), role, escalation.resolvedAt(),
                escalation.resolvedBy(), escalation.resolutionType() == null ? null : escalation.resolutionType().name(),
                escalation.resolutionNote(), escalation.closureReason() == null ? null : escalation.closureReason().name());
    }
}
