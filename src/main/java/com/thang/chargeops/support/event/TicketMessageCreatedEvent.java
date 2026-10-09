package com.thang.chargeops.support.event;

import com.thang.chargeops.support.dto.response.TicketMessageResponse;

import java.util.UUID;

/**
 * Domain event published when a new message is successfully added to a support ticket.
 * Handled AFTER_COMMIT to broadcast via STOMP WebSocket topic.
 */
public record TicketMessageCreatedEvent(
        UUID ticketId,
        TicketMessageResponse message
) {
}
