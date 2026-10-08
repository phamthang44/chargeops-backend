package com.thang.chargeops.infra.websocket;

import com.thang.chargeops.station.event.EquipmentChangeType;
import com.thang.chargeops.station.event.EquipmentStateChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class StationHardwareHintPublisherTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private StationHardwareHintPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new StationHardwareHintPublisher(messagingTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendsCompactHardwareHintToStationTopic() {
        UUID stationId = UUID.randomUUID();

        publisher.onEquipmentStateChanged(event(stationId));

        ArgumentCaptor<Message<byte[]>> captor =
                ArgumentCaptor.forClass((Class<Message<byte[]>>) (Class<?>) Message.class);
        verify(messagingTemplate).send(
                eq("/topic/stations/" + stationId + "/hardware"),
                captor.capture()
        );

        Message<byte[]> message = captor.getValue();
        byte[] payload = message.getPayload();
        String body = new String(payload, StandardCharsets.UTF_8);

        assertThat(body).isEqualTo("{\"v\":1,\"type\":\"HARDWARE_CHANGED\"}");
        assertThat(payload).hasSize(33);
        assertThat(payload.length).isLessThan(100);
        assertThat(message.getHeaders().get(MessageHeaders.CONTENT_TYPE))
                .isEqualTo(MediaType.APPLICATION_JSON);
        // Wire contract v1: no station id, no domain fields, no actor/reason.
        assertThat(body).doesNotContain("station", "connector", "actor", "reason",
                stationId.toString());
    }

    @Test
    void hintPayloadConstantMatchesWireContract() {
        assertThat(StationHardwareHintPublisher.HINT_PAYLOAD.getBytes(StandardCharsets.UTF_8))
                .hasSize(33);
    }

    @Test
    void sendFailureAfterCommitIsSwallowed() {
        doThrow(new MessagingException("broker unavailable"))
                .when(messagingTemplate).send(anyString(), any());

        assertThatNoException().isThrownBy(
                () -> publisher.onEquipmentStateChanged(event(UUID.randomUUID()))
        );
    }

    private static EquipmentStateChangedEvent event(UUID stationId) {
        return new EquipmentStateChangedEvent(
                stationId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "AVAILABLE",
                "IN_USE",
                EquipmentChangeType.CONNECTOR_RUNTIME,
                Instant.parse("2026-10-01T10:00:00Z")
        );
    }
}
