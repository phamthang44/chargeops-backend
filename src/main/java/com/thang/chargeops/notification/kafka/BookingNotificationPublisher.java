package com.thang.chargeops.notification.kafka;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Network transport only; the future outbox worker owns retries and publish bookkeeping. */
public class BookingNotificationPublisher {
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;
    private final String topic;

    public BookingNotificationPublisher(KafkaTemplate<String, String> kafka, ObjectMapper json, String topic) {
        this.kafka = Objects.requireNonNull(kafka);
        this.json = Objects.requireNonNull(json);
        this.topic = Objects.requireNonNull(topic);
    }

    public CompletableFuture<SendResult<String, String>> publish(BookingNotificationEvent event) {
        Objects.requireNonNull(event, "event is required");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Publish booking notifications outside the database transaction");
        }
        // Stable booking key keeps this aggregate on one partition. Preserve the caller's eventId.
        return kafka.send(topic, event.bookingId().toString(), json.writeValueAsString(event));
    }
}
