package com.thang.chargeops.infra.websocket;

import com.thang.chargeops.notification.event.NotificationHintEvent;
import com.thang.chargeops.notification.event.NotificationHintKind;
import com.thang.chargeops.notification.model.NotificationEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Wire contract v1 of the notification hint (plan #69 §2.2): closed key set, hard byte budget,
 * no business or monetary fields, and a broker failure that must never escape the listener.
 */
@ExtendWith(MockitoExtension.class)
class NotificationHintPublisherTest {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private NotificationHintPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new NotificationHintPublisher(messagingTemplate);
    }

    @Test
    void sendsContractV1JsonToThePrivateUserQueue() {
        String sourceKey = "receipt-1:BOOKING_CONFIRMED";

        publisher.onNotificationHint(NotificationHintEvent.created("keycloak-a", sourceKey));

        Sent sent = verifySent();
        Map<String, Object> body = body(sent);
        assertThat(body.keySet()).containsExactlyInAnyOrder("v", "eventId", "type", "ref");
        assertThat(body.get("v")).isEqualTo(1);
        assertThat(body.get("type")).isEqualTo("NOTIFICATION_CREATED");
        assertThat(body.get("ref")).isEqualTo(sourceKey);
        assertThat(body.get("eventId")).isEqualTo("CREATED:keycloak-a:" + sourceKey);
        assertThat(sent.user()).isEqualTo("keycloak-a");
        assertThat(sent.destination()).isEqualTo(NotificationHintPublisher.USER_QUEUE_DESTINATION);
        assertThat(sent.headers()).containsEntry(MessageHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON);
    }

    @Test
    void payloadNeverLeaksTitleBodyOrMoneyFields() {
        publisher.onNotificationHint(NotificationHintEvent.created(
                "keycloak-a", "6f1d9a10-0000-4000-8000-000000000003:REFUND_SUCCEEDED"));

        Sent sent = verifySent();
        assertThat(body(sent).keySet())
                .containsExactlyInAnyOrder("v", "eventId", "type", "ref")
                .doesNotContain("title", "body", "amount", "currency", "total", "refund",
                        "status", "stationId", "recipientId");
        assertThat(sent.payload())
                .as("plan #69 §2.2: the hint stays under the 200-byte budget")
                .hasSizeLessThan(NotificationHintEvent.MAX_PAYLOAD_BYTES);
    }

    @Test
    void longestKeysStillFitTheByteBudget() {
        String longKey = "0123456789abcdef".repeat(10) + ":RESPONSIBILITY_DECIDED";

        publisher.onNotificationHint(NotificationHintEvent.created("keycloak-a", longKey));

        Sent sent = verifySent();
        Map<String, Object> body = body(sent);
        assertThat(sent.payload()).hasSizeLessThan(NotificationHintEvent.MAX_PAYLOAD_BYTES);
        assertThat(body.get("eventId").toString())
                .hasSizeLessThanOrEqualTo(NotificationHintEvent.EVENT_ID_MAX_LENGTH);
        assertThat(body.get("ref").toString())
                .hasSizeLessThanOrEqualTo(NotificationHintEvent.REF_MAX_LENGTH);
        assertThat(sent.user()).isEqualTo("keycloak-a");
    }

    @Test
    void readAllHintOmitsRefAndKeepsTheClosedKeySet() {
        publisher.onNotificationHint(NotificationHintEvent.readAll("keycloak-a", "driver|booking|all|all"));

        Sent sent = verifySent();
        Map<String, Object> body = body(sent);
        assertThat(body.keySet()).containsExactly("v", "eventId", "type");
        assertThat(body.get("type")).isEqualTo("NOTIFICATION_READ_ALL");
        assertThat(body.get("eventId")).isEqualTo("READ_ALL:keycloak-a:driver|booking|all|all");
    }

    @Test
    void decisionNoticeEmitsCreatedHintOnly() {
        // FULL_REFUND rule (plan #69 §2.3): the hint type comes from the notice kind, never
        // from the decision wording — no refund-ish hint type exists at all.
        publisher.onNotificationHint(NotificationHintEvent.created(
                "keycloak-a", "decision-1:RESPONSIBILITY_DECIDED"));

        assertThat(NotificationHintKind.forNotice(NotificationEventType.RESPONSIBILITY_DECIDED))
                .isEqualTo(NotificationHintKind.CREATED);
        assertThat(body(verifySent()).get("type")).isEqualTo("NOTIFICATION_CREATED");
    }

    @Test
    void brokerFailureIsLoggedAndSwallowedAfterCommit() {
        doThrow(new MessagingException("broker down")).when(messagingTemplate)
                .convertAndSendToUser(anyString(), anyString(), any(), any(Map.class));

        assertThatNoException().isThrownBy(() -> publisher.onNotificationHint(
                NotificationHintEvent.created("keycloak-a", "source")));
    }

    @Test
    void payloadIsUtf8Json() {
        publisher.onNotificationHint(NotificationHintEvent.read("keycloak-a",
                "7a5d4c30-0000-4000-8000-000000000004"));

        assertThat(new String(verifySent().payload(), StandardCharsets.UTF_8)).startsWith("{\"v\":1,");
    }

    // --- helpers ---

    private record Sent(String user, String destination, byte[] payload, Map<String, Object> headers) {}

    private Sent verifySent() {
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> destination = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Map<String, Object>> headers =
                ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);

        verify(messagingTemplate).convertAndSendToUser(
                user.capture(), destination.capture(), payload.capture(), headers.capture());

        return new Sent(user.getValue(), destination.getValue(), (byte[]) payload.getValue(), headers.getValue());
    }

    private Map<String, Object> body(Sent sent) {
        return JSON.readValue(new String(sent.payload(), StandardCharsets.UTF_8), Map.class);
    }
}
