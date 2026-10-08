package com.thang.chargeops.infra.websocket;

import com.thang.chargeops.station.event.EquipmentStateChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.nio.charset.StandardCharsets;

/**
 * Turns {@link EquipmentStateChangedEvent} into the on-wire hardware hint
 * (WebSocket plan #02 §3) on {@code /topic/stations/{stationId}/hardware}.
 *
 * <p>Runs synchronously {@code AFTER_COMMIT} with {@code fallbackExecution=false}: an event
 * published outside a transaction (impossible via the {@code MANDATORY} history methods, but
 * defensive) and a rolled-back transaction never produce a hint. The publisher does not query
 * the database, does not write audit records and never starts a new transaction.
 *
 * <p>Any failure (serialization, broker dispatch) is logged and swallowed: the transaction is
 * already committed, and failing the request would be a lie. Losing a hint only delays a
 * client refresh — REST remains the source of truth and slow polling is the safety net.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StationHardwareHintPublisher {

    /** Wire contract v1 — compact JSON, 33 UTF-8 bytes, &lt; 100-byte budget. */
    public static final String HINT_PAYLOAD = "{\"v\":1,\"type\":\"HARDWARE_CHANGED\"}";

    private final SimpMessagingTemplate messagingTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
    public void onEquipmentStateChanged(EquipmentStateChangedEvent event) {
        String destination = "/topic/stations/" + event.stationId() + "/hardware";
        Message<byte[]> hint = MessageBuilder
                .withPayload(HINT_PAYLOAD.getBytes(StandardCharsets.UTF_8))
                .setHeader(MessageHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
                .build();
        try {
            messagingTemplate.send(destination, hint);
        } catch (RuntimeException ex) {
            log.warn("Hardware hint to {} dropped after commit ({}): {}",
                    destination, ex.getClass().getSimpleName(), ex.getMessage());
        }
    }
}
