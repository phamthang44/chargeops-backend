package com.thang.chargeops.support.dto.response;

import com.thang.chargeops.support.model.TicketActorKind;

import java.time.Instant;
import java.util.UUID;

public record TicketMessageResponse(
        UUID messageId,
        String authorDisplayName,
        TicketActorKind authorKind,
        String body,
        Instant createdAt
) {
}
