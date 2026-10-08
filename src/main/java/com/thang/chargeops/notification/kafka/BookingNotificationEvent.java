package com.thang.chargeops.notification.kafka;

import com.thang.chargeops.notification.model.NotificationAudience;
import com.thang.chargeops.notification.model.NotificationEventType;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Versioned transport contract. Email content and addresses are resolved at delivery time. */
public record BookingNotificationEvent(
        UUID eventId,
        int schemaVersion,
        NotificationEventType eventType,
        String sourceEventKey,
        UUID bookingId,
        UUID stationId,
        UUID recipientId,
        NotificationAudience audience,
        Instant occurredAt
) {
    public static final int SCHEMA_VERSION = 1;

    public BookingNotificationEvent {
        Objects.requireNonNull(eventId, "eventId is required");
        Objects.requireNonNull(bookingId, "bookingId is required");
        Objects.requireNonNull(stationId, "stationId is required");
        Objects.requireNonNull(recipientId, "recipientId is required");
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported booking notification schema version");
        }
        boolean driver = audience == NotificationAudience.DRIVER && eventType != null
                && java.util.Set.of(NotificationEventType.BOOKING_CONFIRMED, NotificationEventType.BOOKING_CANCELLED,
                    NotificationEventType.BOOKING_NO_SHOW, NotificationEventType.BOOKING_COMPLETED,
                    NotificationEventType.BOOKING_REMINDER, NotificationEventType.RESPONSIBILITY_DECIDED,
                    NotificationEventType.REFUND_PENDING, NotificationEventType.REFUND_SUCCEEDED).contains(eventType);
        boolean owner = audience == NotificationAudience.OWNER
                && (eventType == NotificationEventType.REFUND_ATTEMPT_FAILED || eventType == NotificationEventType.REFUND_SUCCEEDED);
        if (!driver && !owner) {
            throw new IllegalArgumentException("Unsupported event recipient matrix");
        }
        if (sourceEventKey == null || sourceEventKey.isBlank() || sourceEventKey.length() > 255) {
            throw new IllegalArgumentException("A sourceEventKey of 1 to 255 characters is required");
        }
    }
}
