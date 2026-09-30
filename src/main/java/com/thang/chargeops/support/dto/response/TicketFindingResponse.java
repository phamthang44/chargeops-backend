package com.thang.chargeops.support.dto.response;

import com.thang.chargeops.support.model.TicketFindingConclusion;

import java.time.Instant;
import java.util.UUID;

public record TicketFindingResponse(
        UUID findingId,
        TicketFindingConclusion conclusion,
        Instant affectedAt,
        String reason,
        Instant recordedAt,
        UUID recordedBy
) {
}
