package com.thang.chargeops.infra.websocket;

import com.thang.chargeops.notification.event.NotificationHintEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns {@link NotificationHintEvent} into the on-wire notification hint (BKG-071 plan #69 §2.2)
 * on the private {@code /user/queue/notifications} destination.
 *
 * <p>Runs synchronously {@code AFTER_COMMIT} with {@code fallbackExecution=false}: an event
 * published outside a transaction or in a rolled-back one never produces a hint. Everything the
 * hint needs (recipient keycloak id, dedupe token, ref) was captured inside the transaction —
 * the listener never queries the database, never reads the {@code SecurityContext} and never
 * starts a new transaction.
 *
 * <p>{@code convertAndSendToUser} addresses exactly one principal: Spring resolves
 * {@code /user/{keycloakId}/queue/notifications} to the sessions of that user, so a session can
 * only ever receive its own hints.
 *
 * <p>Any failure (serialization, broker dispatch) is logged and swallowed: the transaction is
 * already committed, and failing the request would be a lie. Losing a hint only delays a client
 * refresh — REST remains the source of truth and slow polling is the safety net.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationHintPublisher {

    /** Destination part handed to {@code convertAndSendToUser} (Spring prefixes {@code /user/}). */
    public static final String USER_QUEUE_DESTINATION = "/queue/notifications";

    /** Wire contract v1 — every hint stays within {@link NotificationHintEvent#MAX_PAYLOAD_BYTES}. */
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final SimpMessagingTemplate messagingTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
    public void onNotificationHint(NotificationHintEvent event) {
        String destination = USER_QUEUE_DESTINATION;
        byte[] payload = render(event, event.ref());
        if (payload.length > NotificationHintEvent.MAX_PAYLOAD_BYTES && event.ref() != null) {
            // Defensive byte budget: a multi-byte ref is advisory only, drop it rather than
            // breach the 200-byte wire contract.
            payload = render(event, null);
        }
        Map<String, Object> headers = Map.of(MessageHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON);
        try {
            messagingTemplate.convertAndSendToUser(
                    event.recipientKeycloakId(), destination, payload, headers);
        } catch (RuntimeException ex) {
            log.warn("Notification hint to {} dropped after commit ({}): {}",
                    destination, ex.getClass().getSimpleName(), ex.getMessage());
        }
    }

    private byte[] render(NotificationHintEvent event, String ref) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("v", 1);
        body.put("eventId", event.eventId());
        body.put("type", event.kind().wireType());
        if (ref != null) {
            body.put("ref", ref);
        }
        return JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
    }
}
