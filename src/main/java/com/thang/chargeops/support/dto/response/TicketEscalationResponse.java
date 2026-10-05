package com.thang.chargeops.support.dto.response;

import java.time.Instant;
import java.util.UUID;
import com.thang.chargeops.support.model.TicketEscalationClosureReason;
import com.thang.chargeops.support.model.TicketEscalationResolutionType;

public record TicketEscalationResponse(UUID ticketId, UUID requestedBy, Instant requestedAt, String reason,
                                       Instant resolvedAt, UUID resolvedBy,
                                       TicketEscalationResolutionType resolutionType,
                                       String resolutionNote, TicketEscalationClosureReason closureReason) {
    public TicketEscalationResponse(UUID ticketId, UUID requestedBy, Instant requestedAt, String reason) {
        this(ticketId, requestedBy, requestedAt, reason, null, null, null, null, null);
    }
}
