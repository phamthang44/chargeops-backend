package com.thang.chargeops.infra.websocket;

import com.thang.chargeops.support.event.TicketMessageCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Listens for {@link TicketMessageCreatedEvent} after database transaction commit
 * and broadcasts the message to the corresponding ticket topic: {@code /topic/tickets/{ticketId}/messages}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TicketMessagePublisher {

    private final SimpMessagingTemplate messagingTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
    public void onTicketMessageCreated(TicketMessageCreatedEvent event) {
        String destination = "/topic/tickets/" + event.ticketId() + "/messages";
        try {
            messagingTemplate.convertAndSend(destination, event.message());
            log.info("Broadcasted ticket message to {} (messageId: {})", destination, event.message().messageId());
        } catch (RuntimeException ex) {
            log.error("Failed to broadcast ticket message to {}: {}", destination, ex.getMessage(), ex);
        }
    }
}
