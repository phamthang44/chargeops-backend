package com.thang.chargeops.notification.kafka;

import com.thang.chargeops.notification.email.NotificationEmailDeliveryStore;
import com.thang.chargeops.notification.model.NotificationEventType;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;

/** Listener return happens after the proxied delivery store commits. No inbox mutation or SMTP here. */
public class BookingNotificationEmailConsumer {
    private static final Set<String> TEXT_FIELDS = Set.of("eventId", "eventType", "sourceEventKey", "bookingId",
            "stationId", "recipientId", "audience", "occurredAt");
    private final NotificationEmailDeliveryStore deliveries;
    private final ObjectMapper json;

    public BookingNotificationEmailConsumer(NotificationEmailDeliveryStore deliveries, ObjectMapper json) {
        this.deliveries = deliveries;
        this.json = json;
    }

    @KafkaListener(id = "booking-email-ingestion",
            topics = "${app.notification.kafka.topic:chargeops.booking.notifications.v1}",
            groupId = "${app.notification.kafka.consumer.group-id:chargeops-booking-email-v1}",
            containerFactory = "bookingEmailKafkaListenerContainerFactory")
    public void receiveConfirmed(ConsumerRecord<String, String> record) {
        BookingNotificationEvent event;
        try {
            var tree = json.readTree(record.value());
            if (tree == null || !tree.isObject() || tree.size() != 9
                    || !tree.has("schemaVersion") || !tree.get("schemaVersion").isIntegralNumber()
                    || tree.get("schemaVersion").intValue() != BookingNotificationEvent.SCHEMA_VERSION
                    || TEXT_FIELDS.stream().anyMatch(field -> !tree.has(field) || !tree.get(field).isString())) {
                throw new IllegalArgumentException();
            }
            event = json.readerFor(BookingNotificationEvent.class)
                    .with(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(record.value());
            if (!event.bookingId().toString().equals(record.key())) throw new IllegalArgumentException();
        } catch (RuntimeException malformed) {
            throw new InvalidBookingNotificationException("INVALID_EVENT_CONTRACT_OR_KEY");
        }
        // Do not catch DB/transaction failures: the container must retain the offset.
        if (event.eventType() == NotificationEventType.BOOKING_CANCELLED) {
            deliveries.captureCancelled(event);
        } else if (event.eventType() == NotificationEventType.BOOKING_CONFIRMED) {
            deliveries.captureConfirmed(event);
        } else {
            deliveries.captureEvent(event);
        }
    }
}
