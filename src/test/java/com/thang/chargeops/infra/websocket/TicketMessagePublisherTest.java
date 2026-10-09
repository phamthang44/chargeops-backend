package com.thang.chargeops.infra.websocket;

import com.thang.chargeops.support.dto.response.TicketMessageResponse;
import com.thang.chargeops.support.event.TicketMessageCreatedEvent;
import com.thang.chargeops.support.model.TicketActorKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TicketMessagePublisherTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private TicketMessagePublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new TicketMessagePublisher(messagingTemplate);
    }

    @Test
    void broadcastsMessageToExactTicketTopic() {
        UUID ticketId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        TicketMessageResponse response = new TicketMessageResponse(
                messageId, "Admin", TicketActorKind.ADMIN, "Hello test", Instant.now(), authorId
        );
        TicketMessageCreatedEvent event = new TicketMessageCreatedEvent(ticketId, response);

        publisher.onTicketMessageCreated(event);

        verify(messagingTemplate).convertAndSend(
                eq("/topic/tickets/" + ticketId + "/messages"),
                eq(response)
        );
    }

    @Test
    void sendFailureAfterCommitIsSwallowed() {
        doThrow(new MessagingException("broker unavailable"))
                .when(messagingTemplate).convertAndSend(anyString(), any(Object.class));

        UUID ticketId = UUID.randomUUID();
        TicketMessageResponse response = new TicketMessageResponse(
                UUID.randomUUID(), "Admin", TicketActorKind.ADMIN, "Hello test", Instant.now(), UUID.randomUUID()
        );

        assertThatNoException().isThrownBy(
                () -> publisher.onTicketMessageCreated(new TicketMessageCreatedEvent(ticketId, response))
        );
    }
}
