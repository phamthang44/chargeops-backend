package com.thang.chargeops.support.dto.response;

import com.thang.chargeops.support.model.TicketCategory;
import com.thang.chargeops.support.model.TicketPriority;
import com.thang.chargeops.support.model.TicketStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TicketResponse(
        UUID ticketId,
        String ticketCode,
        TicketCategory category,
        TicketPriority priority,
        String subject,
        TicketStatus status,
        Long version,
        UUID bookingId,
        UUID stationId,
        UUID reporterId,
        UUID assignedHandlerId,
        Instant createdAt,
        List<TicketMessageResponse> messages,
        List<TicketFindingResponse> findings,
        List<UUID> refundIds
) {
}
