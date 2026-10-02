package com.thang.chargeops.support.dto.response;

import java.time.Instant;
import java.util.UUID;

/** Request facts plus the requester's business role in this ticket. */
public record TicketEscalationDetailResponse(
        UUID ticketId,
        UUID requestedBy,
        Instant requestedAt,
        String reason,
        String requestedByRole
) {
    public static TicketEscalationDetailResponse from(TicketEscalationResponse escalation,
                                                       UUID ownerId, UUID reporterId) {
        if (escalation == null) return null;
        String role = escalation.requestedBy().equals(ownerId) ? "owner"
                : escalation.requestedBy().equals(reporterId) ? "driver" : null;
        return new TicketEscalationDetailResponse(escalation.ticketId(), escalation.requestedBy(),
                escalation.requestedAt(), escalation.reason(), role);
    }
}
