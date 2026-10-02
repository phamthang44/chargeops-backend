package com.thang.chargeops.support.dto.response;

import java.time.Instant;
import java.util.UUID;

public record TicketEscalationResponse(UUID ticketId, UUID requestedBy, Instant requestedAt, String reason) {}
